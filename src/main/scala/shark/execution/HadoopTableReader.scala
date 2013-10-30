/*
 * Copyright (C) 2012 The Regents of The University California.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shark.execution

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path, PathFilter}
import org.apache.hadoop.mapred.{FileInputFormat, InputFormat, JobConf}
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.metastore.api.Constants.META_TABLE_PARTITION_COLUMNS
import org.apache.hadoop.hive.ql.exec.Utilities
import org.apache.hadoop.hive.ql.metadata.{Partition, Table => HiveTable}
import org.apache.hadoop.hive.ql.plan.{PartitionDesc, TableDesc}
import org.apache.hadoop.io.Writable

import org.apache.spark.rdd.{EmptyRDD, HadoopRDD, RDD, UnionRDD}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.SerializableWritable

import shark.{SharkEnv, Utils}


/**
 * Helper class for scanning tables stored in Hadoop - e.g., to read Hive tables that reside in the
 * data warehouse directory.
 */
class HadoopTableReader(@transient _tableDesc: TableDesc, @transient _localHConf: HiveConf)
  extends TableReader {

  // Choose the minimum number of splits. If mapred.map.tasks is set, then use that unless
  // it is smaller than what Spark suggests.
  private val _minSplitsPerRDD = math.max(
    _localHConf.getInt("mapred.map.tasks", 1), SharkEnv.sc.defaultMinSplits)

  // Add security credentials before broadcasting the Hive configuration, which is used accross all
  // reads done by an instance of this class.
  HadoopTableReader.addCredentialsToConf(_localHConf)
  private val _broadcastedHiveConf = SharkEnv.sc.broadcast(new SerializableWritable(_localHConf))

  def broadcastedHiveConf = _broadcastedHiveConf

  override def makeRDDForTable(hiveTable: HiveTable): RDD[_] =
    makeRDDForTable(hiveTable, filterOpt = None)
  
  /**
   * Creates a Hadoop RDD to read data from the target table's data directory. Returns a transformed
   * RDD that contains deserialized rows.
   */
  def makeRDDForTable(hiveTable: HiveTable, filterOpt: Option[PathFilter] = None): RDD[_] = {
    assert(!hiveTable.isPartitioned, """makeRDDForTable() cannot be called on a partitioned table,
      since input formats may differ across partitions. Use makeRDDForTablePartitions() instead.""")

    // Create local references to member variables, so that the entire `this` object won't be
    // serialized in the closure below.
    val tableDesc = _tableDesc
    val broadcastedHiveConf = _broadcastedHiveConf

    val tablePath = hiveTable.getPath
    val inputPathStr = applyFilterIfNeeded(tablePath, filterOpt)

    logDebug("Table input: %s".format(tablePath))
    val ifc = hiveTable.getInputFormatClass
      .asInstanceOf[java.lang.Class[InputFormat[Writable, Writable]]]
    val hadoopRDD = createHadoopRdd(tableDesc, inputPathStr, ifc)

    val deserializedHadoopRDD = hadoopRDD.mapPartitions { iter =>
      val hconf = broadcastedHiveConf.value.value
      val deserializer = tableDesc.getDeserializerClass().newInstance()
      deserializer.initialize(hconf, tableDesc.getProperties)

      // Deserialize each Writable to get the row value.
      iter.map { value =>
        value match {
          case v: Writable => deserializer.deserialize(v)
          case _ => throw new RuntimeException("Failed to match " + value.toString)
        }
      }
    }
    deserializedHadoopRDD
  }

  override def makeRDDForPartitionedTable(partitions: Seq[Partition]): RDD[_] =
    makeRDDForPartitionedTable(partitions, filterOpt = None)

  /**
   * Create a HadoopRDD for every partition key specified in the query. Note that for on-disk Hive
   * tables, a data directory is created for each partition corresponding to keys specified using
   * 'PARTITION BY'.
   */
  def makeRDDForPartitionedTable(
      partitions: Seq[Partition],
      filterOpt: Option[PathFilter]): RDD[_] = {
    val hivePartitionRDDs = partitions.map { partition =>
      val partDesc = Utilities.getPartitionDesc(partition)
      val partPath = partition.getPartitionPath
      val inputPathStr = applyFilterIfNeeded(partPath, filterOpt)
      val ifc = partDesc.getInputFileFormatClass
        .asInstanceOf[java.lang.Class[InputFormat[Writable, Writable]]]
      // Get partition field info
      val partSpec = partDesc.getPartSpec()
      val partProps = partDesc.getProperties()
      val partDeserializer = partDesc.getDeserializerClass()

      val partColsDelimited = partProps.getProperty(META_TABLE_PARTITION_COLUMNS)
      // Partitioning columns are delimited by "/"
      val partCols = partColsDelimited.trim().split("/").toSeq
      // 'partValues[i]' contains the value for the partitioning column at 'partCols[i]'.
      val partValues = if (partSpec == null) {
        Array.fill(partCols.size)(new String)
      } else {
        partCols.map(col => new String(partSpec.get(col))).toArray
      }

      // Create local references so that the outer object isn't serialized.
      val tableDesc = _tableDesc
      val broadcastedHiveConf = _broadcastedHiveConf

      val hivePartitionRDD = createHadoopRdd(tableDesc, inputPathStr, ifc)
      hivePartitionRDD.mapPartitions { iter =>
        val hconf = broadcastedHiveConf.value.value
        val rowWithPartArr = new Array[Object](2)
        // Map each tuple to a row object
        iter.map { value =>
          val deserializer = partDeserializer.newInstance()
          deserializer.initialize(hconf, partProps)
          val deserializedRow = deserializer.deserialize(value) // LazyStruct
          rowWithPartArr.update(0, deserializedRow)
          rowWithPartArr.update(1, partValues)
          rowWithPartArr.asInstanceOf[Object]
        }
      }
    }
    // Even if we don't use any partitions, we still need an empty RDD
    if (hivePartitionRDDs.size == 0) {
      new EmptyRDD[Object](SharkEnv.sc)
    } else {
      new UnionRDD(hivePartitionRDDs(0).context, hivePartitionRDDs)
    }
  }

  /**
   * If `filterOpt` is defined, then it will be used to filter files from `path`. These files are
   * returned in a single, comma-separated string.
   */
  private def applyFilterIfNeeded(path: Path, filterOpt: Option[PathFilter]): String = {
    filterOpt match {
      case Some(filter) => {
        val fs = path.getFileSystem(_localHConf)
        val filteredFiles = fs.listStatus(path, filter).map(_.getPath.toString)
        filteredFiles.mkString(",")
      }
      case None => path.toString
    }
  }

  /**
   * Creates a HadoopRDD based on the broadcasted HiveConf and other job properties that will be
   * applied locally on each slave.
   */
  private def createHadoopRdd(
      tableDesc: TableDesc,
      path: String,
      inputFormatClass: Class[InputFormat[Writable, Writable]])
    : RDD[Writable] = {
    val initializeJobConfFunc = HadoopTableReader.initializeLocalJobConfFunc(path, tableDesc) _

    val rdd = new HadoopRDD(
      SharkEnv.sc,
      _broadcastedHiveConf.asInstanceOf[Broadcast[SerializableWritable[Configuration]]],
      Some(initializeJobConfFunc),
      inputFormatClass,
      classOf[Writable],
      classOf[Writable],
      _minSplitsPerRDD)

    // Only take the value (skip the key) because Hive works only with values.
    rdd.map(_._2)
  }
}

object HadoopTableReader {

  /**
   * Curried. After given an argument for 'path', the resulting JobConf => Unit closure is used to
   * instantiate a HadoopRDD.
   */
  def initializeLocalJobConfFunc(path: String, tableDesc: TableDesc)(jobConf: JobConf) {
    FileInputFormat.setInputPaths(jobConf, path)
    if (tableDesc != null) {
      Utilities.copyTableJobPropertiesToConf(tableDesc, jobConf)
    }
    val bufferSize = System.getProperty("spark.buffer.size", "65536")
    jobConf.set("io.file.buffer.size", bufferSize)
  }

  /** Adds S3 credentials to the `conf`. */
  def addCredentialsToConf(conf: Configuration) {
    // Set s3/s3n credentials. Setting them in localJobConf ensures the settings propagate
    // from Spark's master all the way to Spark's slaves.
    var s3varsSet = false
    val s3vars = Seq("fs.s3n.awsAccessKeyId", "fs.s3n.awsSecretAccessKey",
      "fs.s3.awsAccessKeyId", "fs.s3.awsSecretAccessKey").foreach { variableName =>
      if (conf.get(variableName) != null) {
        s3varsSet = true
      }
    }

    // If none of the s3 credentials are set in Hive conf, try use the environmental
    // variables for credentials.
    if (!s3varsSet) {
      Utils.setAwsCredentials(conf)
    }
  }
}
