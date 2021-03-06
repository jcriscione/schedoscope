/**
  * Copyright 2016 Otto (GmbH & Co KG)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package org.schedoscope.dsl.transformations

import org.apache.hadoop.mapreduce.Job
import org.schedoscope.Schedoscope
import org.schedoscope.dsl.{Field, View}
import org.schedoscope.export.bigquery.BigQueryExportJob
import org.schedoscope.export.ftp.FtpExportJob
import org.schedoscope.export.ftp.outputformat.FileOutputType
import org.schedoscope.export.ftp.upload.FileCompressionCodec
import org.schedoscope.export.jdbc.JdbcExportJob
import org.schedoscope.export.jdbc.exception.{RetryException, UnrecoverableException}
import org.schedoscope.export.kafka.KafkaExportJob
import org.schedoscope.export.kafka.options.{CleanupPolicy, CompressionCodec, OutputEncoding, ProducerType}
import org.schedoscope.export.redis.RedisExportJob
import org.schedoscope.scheduler.driver._

/**
  * A helper class to with constructors for exportTo() MR jobs.
  */
object Export {

  /**
    * This function configures the JDBC export job and returns a MapreduceTransformation.
    *
    * @param v                 The view to export
    * @param jdbcConnection    A JDBC connection string
    * @param dbUser            The database user
    * @param dbPass            the database password
    * @param distributionKey   The distribution key (only relevant for exasol)
    * @param exportSalt        an optional salt when anonymizing fields
    * @param storageEngine     The underlying storage engine (only relevant for MySQL)
    * @param numReducers       The number of reducers, defines concurrency
    * @param commitSize        The size of batches for JDBC inserts
    * @param isKerberized      Is the cluster kerberized?
    * @param kerberosPrincipal The kerberos principal to use
    * @param metastoreUri      The thrift URI to the metastore
    */
  def Jdbc(
            v: View,
            jdbcConnection: String,
            dbUser: String = null,
            dbPass: String = null,
            distributionKey: Field[_] = null,
            exportSalt: String = Schedoscope.settings.exportSalt,
            storageEngine: String = Schedoscope.settings.jdbcStorageEngine,
            numReducers: Int = Schedoscope.settings.jdbcExportNumReducers,
            commitSize: Int = Schedoscope.settings.jdbcExportBatchSize,
            isKerberized: Boolean = !Schedoscope.settings.kerberosPrincipal.isEmpty(),
            kerberosPrincipal: String = Schedoscope.settings.kerberosPrincipal,
            metastoreUri: String = Schedoscope.settings.metastoreUri) = {

    val t = MapreduceTransformation(
      v,
      (conf) => {

        val filter = v.partitionParameters
          .map { p => s"${p.n} = '${p.v.get}'" }
          .mkString(" and ")

        val distributionField = if (distributionKey != null) distributionKey.n else null

        val anonFields = v.fields
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        val anonParameters = v.partitionParameters
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        new JdbcExportJob().configure(
          conf.get("schedoscope.export.isKerberized").get.asInstanceOf[Boolean],
          conf.get("schedoscope.export.metastoreUri").get.asInstanceOf[String],
          conf.get("schedoscope.export.kerberosPrincipal").get.asInstanceOf[String],
          conf.get("schedoscope.export.jdbcConnection").get.asInstanceOf[String],
          conf.get("schedoscope.export.dbUser").getOrElse(null).asInstanceOf[String],
          conf.get("schedoscope.export.dbPass").getOrElse(null).asInstanceOf[String],
          v.dbName,
          v.n,
          filter,
          conf.get("schedoscope.export.storageEngine").get.asInstanceOf[String],
          distributionField,
          conf.get("schedoscope.export.numReducers").get.asInstanceOf[Int],
          conf.get("schedoscope.export.commitSize").get.asInstanceOf[Int],
          anonFields ++ anonParameters,
          conf.get("schedoscope.export.salt").get.asInstanceOf[String])

      },
      jdbcPostCommit)

    t.directoriesToDelete = List()
    t.configureWith(
      Map(
        "schedoscope.export.jdbcConnection" -> jdbcConnection,
        "schedoscope.export.dbUser" -> dbUser,
        "schedoscope.export.dbPass" -> dbPass,
        "schedoscope.export.storageEngine" -> storageEngine,
        "schedoscope.export.numReducers" -> numReducers,
        "schedoscope.export.commitSize" -> commitSize,
        "schedoscope.export.salt" -> exportSalt,
        "schedoscope.export.isKerberized" -> isKerberized,
        "schedoscope.export.kerberosPrincipal" -> kerberosPrincipal,
        "schedoscope.export.metastoreUri" -> metastoreUri))

  }

  /**
    * This function runs the post commit action for a JDBC export and finalizes the database tables.
    *
    * @param job      The MR job object
    * @param driver   The schedoscope driver
    * @param runState The job's runstate
    */
  def jdbcPostCommit(
                      job: Job,
                      driver: Driver[MapreduceBaseTransformation],
                      runState: DriverRunState[MapreduceBaseTransformation]): DriverRunState[MapreduceBaseTransformation] = {

    val jobConfigurer = new JdbcExportJob()

    try {

      jobConfigurer.postCommit(runState.isInstanceOf[DriverRunSucceeded[MapreduceBaseTransformation]], job.getConfiguration)
      runState

    } catch {
      case ex: RetryException => throw RetryableDriverException(ex.getMessage, ex)
      case ex: UnrecoverableException => DriverRunFailed(driver, ex.getMessage, ex)
    }
  }

  /**
    * This function prepares a MapReduce job for exporting a given view to BigQuery.
    *
    * @param v                         the view to export.
    * @param projectId                 GCP project ID under which exported BigQuery dataset will be created. If not set,
    *                                  this is the default GCP project of the current user. Can be globally configured by
    *                                  setting schedoscope.export.bigQuery.projectId
    * @param gcpKey                    GCP key in JSON format to use for authentication when exporting to BigQuery.
    *                                  If not set, the local gcloud key of the user running Schedoscope is used.
    *                                  Can be globally configured by setting schedoscope.export.bigQuery.gcpKey
    * @param gcpKeyFile                An absolute path pointing to the GCP key in JSON format to use for authentication
    *                                  when exporting to BigQuery. If not set, the local gcloud key of the user running
    *                                  Schedoscope is used (or gcpKey).
    * @param storageBucket             GCP Cloud Storage bucket to use for temporary storage while exporting to BigQuery.
    *                                  Defaults to "schedoscope-bigquery-export". Can be globally configured by
    *                                  setting schedoscope.export.bigQuery.storageBucket
    * @param storageBucketFolderPrefix Folder prefix to apply to blobs in the GCP Cloud Storage bucket while exporting
    *                                  to BigQuery. Defaults to "". Can be globally configured by
    *                                  setting schedoscope.export.bigQuery.storageBucketFolderPrefix
    * @param storageBucketRegion       GCP Cloud Storage bucket region to use for exporting to BigQuery. Defaults to
    *                                  europe-west3.
    *                                  Can be globally configured by setting schedoscope.export.bigQuery.storageBucketRegion
    * @param dataLocation              GCP data storage location of exported data within BigQuery. Defaults to EU.
    *                                  Can be globally configured by setting schedoscope.export.bigQuery.dataLocation
    * @param numReducers               Number of reducers to use for BigQuery export. Defines the parallelism. Defaults to 10.
    *                                  Can be globally configured by setting schedoscope.export.bigQuery.numReducers
    * @param flushInterval             Number of records to batch before flushing data to GCP Cloud Storage. Defaults to 10000.
    *                                  Can be globally configured by setting schedoscope.export.bigQuery.flushInterval
    * @param proxyHost                 Host of proxy to use for GCP API access. Set to empty, i.e., no proxy to use.
    * @param proxyPort                 Port of proxy to use for GCP API access. Set to empty, i.e., no proxy to use.
    * @param isKerberized              Is the cluster kerberized?
    * @param kerberosPrincipal         Kerberos principal to use. Can be globally configured by setting schedoscope.kerberos.principal
    * @param metastoreUri              URI of the metastore. Can be globally configured by setting schedoscope.metastore.metastoreUri
    * @param exportSalt                Salt to use for anonymization. schedoscope.export.salt
    * @return the MapReduce transformation performing the export
    */
  def BigQuery(
                v: View,
                projectId: String = if (Schedoscope.settings.bigQueryExportProjectId.isEmpty) null else Schedoscope.settings.bigQueryExportProjectId,
                gcpKey: String = if (Schedoscope.settings.bigQueryExportGcpKey.isEmpty) null else Schedoscope.settings.bigQueryExportGcpKey,
                gcpKeyFile: String = null,
                storageBucket: String = Schedoscope.settings.bigQueryExportStorageBucket,
                storageBucketFolderPrefix: String = Schedoscope.settings.bigQueryExportStorageBucketFolderPrefix,
                storageBucketRegion: String = Schedoscope.settings.bigQueryExportStorageBucketRegion,
                dataLocation: String = Schedoscope.settings.bigQueryExportDataLocation,
                numReducers: Int = Schedoscope.settings.bigQueryExportNumReducers,
                flushInterval: Long = Schedoscope.settings.bigQueryExportFlushInterval,
                proxyHost: String = if (Schedoscope.settings.bigQueryExportProxyHost.isEmpty) null else Schedoscope.settings.bigQueryExportProxyHost,
                proxyPort: String = if (Schedoscope.settings.bigQueryExportProxyPort.isEmpty) null else Schedoscope.settings.bigQueryExportProxyPort,
                isKerberized: Boolean = !Schedoscope.settings.kerberosPrincipal.isEmpty(),
                kerberosPrincipal: String = Schedoscope.settings.kerberosPrincipal,
                metastoreUri: String = Schedoscope.settings.metastoreUri,
                exportSalt: String = Schedoscope.settings.exportSalt
              ) = {

    val t = MapreduceTransformation(
      v,
      (conf) => {

        val filter = v.partitionParameters
          .map { p => s"${p.n} = '${p.v.get}'" }
          .mkString(" and ")

        val anonFields = v.fields
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        val anonParameters = v.partitionParameters
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        val bigQueryPartitionDate: Option[String] = v.partitionParameters
          .filter { p => Set("month_id", "date_id").contains(p.n) }
          .map {
            p =>
              if (p.n == "month_id")
                p.v.get.asInstanceOf[String] + "01"
              else
                p.v.get.asInstanceOf[String]
          }
          .headOption

        val bigQueryPartitionSuffixes = v.partitionParameters
          .filter { p => !Set("year", "month", "day", "date_id", "month_id").contains(p.n) }
          .sortBy {
            _.n
          }
          .map {
            _.v.get
          }
          .mkString("_").toLowerCase

        val baseJobParameters: Seq[String] = Seq() ++
          (
            if (conf("schedoscope.export.isKerberized").asInstanceOf[Boolean])
              Seq("-s", "-p", conf("schedoscope.export.kerberosPrincipal").asInstanceOf[String])
            else
              Nil
            ) ++
          Seq("-m", conf("schedoscope.export.metastoreUri").asInstanceOf[String]) ++
          Seq("-d", v.dbName) ++
          Seq("-t", v.n) ++
          (
            if (!filter.isEmpty)
              Seq("-i", filter)
            else
              Nil
            ) ++
          Seq("-c", conf("schedoscope.export.numReducers").asInstanceOf[Integer].toString) ++
          Seq("-F", conf("schedoscope.export.flushInterval").asInstanceOf[Long].toString) ++
          (
            if (!(anonFields ++ anonParameters).isEmpty)
              Seq("-A", (anonFields ++ anonParameters).mkString(" "), "-S", conf("schedoscope.export.exportSalt").asInstanceOf[String])
            else
              Nil
            )

        val bigQueryJobParameters: Seq[String] = Seq() ++
          (
            if (conf.contains("schedoscope.export.projectId"))
              Seq("-P", conf("schedoscope.export.projectId").asInstanceOf[String])
            else
              Nil
            ) ++
          (
            if (conf.contains("schedoscope.export.gcpKey"))
              Seq("-k", conf("schedoscope.export.gcpKey").asInstanceOf[String])
            else
              Nil
            ) ++
          (
            if (conf.contains("schedoscope.export.gcpKeyFile"))
              Seq("-K", conf("schedoscope.export.gcpKeyFile").asInstanceOf[String])
            else
              Nil
            ) ++
          (
            if (conf.contains("schedoscope.export.proxyHost"))
              Seq("-y", conf("schedoscope.export.proxyHost").asInstanceOf[String])
            else
              Nil
            ) ++
          (
            if (conf.contains("schedoscope.export.proxyPort"))
              Seq("-Y", conf("schedoscope.export.proxyPort").asInstanceOf[String])
            else
              Nil
            ) ++
          Seq("-l", conf("schedoscope.export.dataLocation").asInstanceOf[String]) ++
          Seq("-b", conf("schedoscope.export.storageBucket").asInstanceOf[String]) ++
          Seq("-f", conf("schedoscope.export.storageBucketFolderPrefix").asInstanceOf[String]) ++
          Seq("-r", conf("schedoscope.export.storageBucketRegion").asInstanceOf[String]) ++
          (
            if (bigQueryPartitionDate.isDefined)
              Seq("-D", bigQueryPartitionDate.get)
            else
              Nil
            ) ++
          (
            if (!bigQueryPartitionSuffixes.isEmpty)
              Seq("-x", bigQueryPartitionSuffixes)
            else
              Nil
            )

        new BigQueryExportJob().createJob((baseJobParameters ++ bigQueryJobParameters).toArray[String])
      },
      bigQueryPostCommit
    )

    t.directoriesToDelete = List()

    t.configureWith(
      Map(
        "schedoscope.export.storageBucket" -> storageBucket,
        "schedoscope.export.storageBucketFolderPrefix" -> storageBucketFolderPrefix,
        "schedoscope.export.storageBucketRegion" -> storageBucketRegion,
        "schedoscope.export.dataLocation" -> dataLocation,
        "schedoscope.export.numReducers" -> numReducers,
        "schedoscope.export.isKerberized" -> isKerberized,
        "schedoscope.export.kerberosPrincipal" -> kerberosPrincipal,
        "schedoscope.export.metastoreUri" -> metastoreUri,
        "schedoscope.export.exportSalt" -> exportSalt,
        "schedoscope.export.flushInterval" -> flushInterval
      ) ++ (if (projectId != null) Seq("schedoscope.export.projectId" -> projectId) else Nil)
        ++ (if (gcpKey != null) Seq("schedoscope.export.gcpKey" -> gcpKey) else Nil)
        ++ (if (gcpKeyFile != null) Seq("schedoscope.export.gcpKeyFile" -> gcpKeyFile) else Nil)
        ++ (if (proxyHost != null) Seq("schedoscope.export.proxyHost" -> proxyHost) else Nil)
        ++ (if (proxyPort != null) Seq("schedoscope.export.proxyPort" -> proxyPort) else Nil)
    )
  }

  /**
    * This function runs the post commit action for a BigQuery export and finalizes the database tables.
    *
    * @param job      The MR job object
    * @param driver   The schedoscope driver
    * @param runState The job's runstate
    */
  def bigQueryPostCommit(
                          job: Job,
                          driver: Driver[MapreduceBaseTransformation],
                          runState: DriverRunState[MapreduceBaseTransformation]): DriverRunState[MapreduceBaseTransformation] = {

    try {

      BigQueryExportJob.finishJob(job, runState.isInstanceOf[DriverRunSucceeded[MapreduceBaseTransformation]])
      runState

    } catch {
      case ex: RetryException => throw RetryableDriverException(ex.getMessage, ex)
      case ex: UnrecoverableException => DriverRunFailed(driver, ex.getMessage, ex)
    }
  }

  /**
    * This function configures the Redis export job and returns a MapreduceTransformation.
    *
    * @param v                 The view
    * @param redisHost         The Redis hostname
    * @param key               The field to use as the Redis key
    * @param value             An optional field to export. If null, all fields are attached to the key as a map. If not null, only that field's value is attached to the key.
    * @param keyPrefix         An optional key prefix
    * @param exportSalt        an optional salt when anonymizing fields
    * @param replace           A flag indicating of existing keys should be replaced (or extended)
    * @param flush             A flag indicating if the key space should be flushed before writing data
    * @param redisPort         The Redis port (default 6379)
    * @param redisKeySpace     The Redis key space (default 0)
    * @param commitSize        The number of events to write before syncing the pipelined writer.
    * @param numReducers       The number of reducers, defines concurrency
    * @param pipeline          A flag indicating that the Redis pipeline mode should be used for writing data
    * @param isKerberized      Is the cluster kerberized?
    * @param kerberosPrincipal The kerberos principal to use
    * @param metastoreUri      The thrift URI to the metastore
    */
  def Redis(
             v: View,
             redisHost: String,
             key: Field[_],
             value: Field[_] = null,
             keyPrefix: String = "",
             exportSalt: String = Schedoscope.settings.exportSalt,
             replace: Boolean = true,
             flush: Boolean = false,
             redisPort: Int = 6379,
             redisPassword: String = null,
             redisKeySpace: Int = 0,
             commitSize: Int = Schedoscope.settings.redisExportBatchSize,
             numReducers: Int = Schedoscope.settings.redisExportNumReducers,
             pipeline: Boolean = Schedoscope.settings.redisExportUsesPipelineMode,
             isKerberized: Boolean = !Schedoscope.settings.kerberosPrincipal.isEmpty(),
             kerberosPrincipal: String = Schedoscope.settings.kerberosPrincipal,
             metastoreUri: String = Schedoscope.settings.metastoreUri) = {

    val t = MapreduceTransformation(
      v,
      (conf) => {

        val filter = v.partitionParameters
          .map { p => s"${p.n} = '${p.v.get}'" }
          .mkString(" and ")

        val valueFieldName = if (value != null) value.n else null

        val anonFields = v.fields
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        val anonParameters = v.partitionParameters
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        new RedisExportJob().configure(
          conf.get("schedoscope.export.isKerberized").get.asInstanceOf[Boolean],
          conf.get("schedoscope.export.metastoreUri").get.asInstanceOf[String],
          conf.get("schedoscope.export.kerberosPrincipal").get.asInstanceOf[String],
          conf.get("schedoscope.export.redisHost").get.asInstanceOf[String],
          conf.get("schedoscope.export.redisPort").get.asInstanceOf[Int],
          conf.get("schedoscope.export.redisPassword").get.asInstanceOf[String],
          conf.get("schedoscope.export.redisKeySpace").get.asInstanceOf[Int],
          v.dbName,
          v.n,
          filter,
          key.n,
          valueFieldName,
          keyPrefix,
          conf.get("schedoscope.export.numReducers").get.asInstanceOf[Int],
          replace,
          conf.get("schedoscope.export.pipeline").get.asInstanceOf[Boolean],
          flush,
          conf.get("schedoscope.export.commitSize").get.asInstanceOf[Int],
          anonFields ++ anonParameters,
          conf.get("schedoscope.export.salt").get.asInstanceOf[String])

      })

    t.directoriesToDelete = List()
    t.configureWith(
      Map(
        "schedoscope.export.redisHost" -> redisHost,
        "schedoscope.export.redisPort" -> redisPort,
        "schedoscope.export.redisPassword" -> redisPassword,
        "schedoscope.export.redisKeySpace" -> redisKeySpace,
        "schedoscope.export.numReducers" -> numReducers,
        "schedoscope.export.pipeline" -> pipeline,
        "schedoscope.export.commitSize" -> commitSize,
        "schedoscope.export.salt" -> exportSalt,
        "schedoscope.export.isKerberized" -> isKerberized,
        "schedoscope.export.kerberosPrincipal" -> kerberosPrincipal,
        "schedoscope.export.metastoreUri" -> metastoreUri))

  }

  /**
    * This function creates a Kafka topic export MapreduceTransformation.
    *
    * @param v                 The view to export
    * @param key               the field to serve as the topic's key
    * @param kafkaHosts        String list of Kafka hosts to communicate with
    * @param zookeeperHosts    String list of zookeeper hosts
    * @param replicationFactor The replication factor, defaults to 1
    * @param exportSalt        an optional salt when anonymizing fields
    * @param producerType      The type of producer to use, defaults to synchronous
    * @param cleanupPolicy     Default cleanup policy is delete
    * @param compressionCodec  Default compression codec is gzip
    * @param encoding          Defines, whether data is to be serialized as strings (one line JSONs) or Avro
    * @param numReducers       number of reducers to use (i.e., the parallelism)
    * @param isKerberized      Is the cluster kerberized?
    * @param kerberosPrincipal The kerberos principal to use
    * @param metastoreUri      The thrift URI to the metastore
    *
    */
  def Kafka(
             v: View,
             key: Field[_],
             kafkaHosts: String,
             zookeeperHosts: String,
             replicationFactor: Int = 1,
             numPartitons: Int = 3,
             exportSalt: String = Schedoscope.settings.exportSalt,
             producerType: ProducerType = ProducerType.sync,
             cleanupPolicy: CleanupPolicy = CleanupPolicy.delete,
             compressionCodec: CompressionCodec = CompressionCodec.gzip,
             encoding: OutputEncoding = OutputEncoding.string,
             numReducers: Int = Schedoscope.settings.kafkaExportNumReducers,
             isKerberized: Boolean = !Schedoscope.settings.kerberosPrincipal.isEmpty(),
             kerberosPrincipal: String = Schedoscope.settings.kerberosPrincipal,
             metastoreUri: String = Schedoscope.settings.metastoreUri) = {

    val t = MapreduceTransformation(
      v,
      (conf) => {

        val filter = v.partitionParameters
          .map { p => s"${p.n} = '${p.v.get}'" }
          .mkString(" and ")

        val anonFields = v.fields
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        val anonParameters = v.partitionParameters
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        new KafkaExportJob().configure(
          conf.get("schedoscope.export.isKerberized").get.asInstanceOf[Boolean],
          conf.get("schedoscope.export.metastoreUri").get.asInstanceOf[String],
          conf.get("schedoscope.export.kerberosPrincipal").get.asInstanceOf[String],
          v.dbName,
          v.n,
          filter,
          key.n,
          conf.get("schedoscope.export.kafkaHosts").get.asInstanceOf[String],
          conf.get("schedoscope.export.zookeeperHosts").get.asInstanceOf[String],
          producerType,
          cleanupPolicy,
          conf.get("schedoscope.export.numPartitions").get.asInstanceOf[Int],
          conf.get("schedoscope.export.replicationFactor").get.asInstanceOf[Int],
          conf.get("schedoscope.export.numReducers").get.asInstanceOf[Int],
          compressionCodec,
          encoding,
          anonFields ++ anonParameters,
          conf.get("schedoscope.export.salt").get.asInstanceOf[String])
      })

    t.directoriesToDelete = List()
    t.configureWith(
      Map(
        "schedoscope.export.kafkaHosts" -> kafkaHosts,
        "schedoscope.export.zookeeperHosts" -> zookeeperHosts,
        "schedoscope.export.numPartitions" -> numPartitons,
        "schedoscope.export.replicationFactor" -> replicationFactor,
        "schedoscope.export.numReducers" -> numReducers,
        "schedoscope.export.salt" -> exportSalt,
        "schedoscope.export.isKerberized" -> isKerberized,
        "schedoscope.export.kerberosPrincipal" -> kerberosPrincipal,
        "schedoscope.export.metastoreUri" -> metastoreUri))
  }

  /**
    * This function configures the (S)FTP export and returns a configured MapReduceTransformation.
    *
    * @param v                 The view to export
    * @param ftpEndpoint       The (s)ftp endpoint.
    * @param ftpUser           The (s)ftp user.
    * @param ftpPass           The (s)ftp passphrase or password.
    * @param filePrefix        A custom file prefix for exported files.
    * @param delimiter         A custom delimiter to use (only CSV export).
    * @param printHeader       To print a header or not (only CSV export)
    * @param keyFile           A private ssh key file. Defaults to ~/.ssh/id_rsa
    * @param fileType          The output file type, either csv or json.
    * @param numReducers       Number of reducers / number of files.
    * @param passiveMode       Enable passive mode for FTP connections.
    * @param userIsRoot        User dir is root for (s)ftp connections.
    * @param cleanHdfsDir      Clean up HDFS temporary files (or not).
    * @param exportSalt        an optional salt when anonymizing fields.
    * @param codec             The compression codec to use, either gzip or bzip2
    * @param isKerberized      A flag indication if Kerberos is enabled.
    * @param kerberosPrincipal The Kerberos principal
    * @param metastoreUri      A string containing the Hive meta store url.
    */
  def Ftp(
           v: View,
           ftpEndpoint: String,
           ftpUser: String,
           ftpPass: String = null,
           filePrefix: String = null,
           delimiter: String = "\t",
           printHeader: Boolean = true,
           keyFile: String = "~/.ssh/id_rsa",
           fileType: FileOutputType = FileOutputType.csv,
           numReducers: Int = Schedoscope.settings.ftpExportNumReducers,
           passiveMode: Boolean = true,
           userIsRoot: Boolean = true,
           cleanHdfsDir: Boolean = true,
           exportSalt: String = Schedoscope.settings.exportSalt,
           codec: FileCompressionCodec = FileCompressionCodec.gzip,
           isKerberized: Boolean = !Schedoscope.settings.kerberosPrincipal.isEmpty(),
           kerberosPrincipal: String = Schedoscope.settings.kerberosPrincipal,
           metastoreUri: String = Schedoscope.settings.metastoreUri) = {

    val t = MapreduceTransformation(
      v,
      (conf) => {
        val filter = v.partitionParameters
          .map { p => s"${p.n} = '${p.v.get}'" }
          .mkString(" and ")

        val anonFields = v.fields
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        val anonParameters = v.partitionParameters
          .filter {
            _.isPrivacySensitive
          }
          .map {
            _.n
          }
          .toArray

        new FtpExportJob().configure(
          conf.get("schedoscope.export.isKerberized").get.asInstanceOf[Boolean],
          conf.get("schedoscope.export.metastoreUri").get.asInstanceOf[String],
          conf.get("schedoscope.export.kerberosPrincipal").get.asInstanceOf[String],
          v.dbName,
          v.n,
          filter,
          conf.get("schedoscope.export.numReducers").get.asInstanceOf[Int],
          anonFields ++ anonParameters,
          conf.get("schedoscope.export.salt").get.asInstanceOf[String],
          conf.get("schedoscope.export.keyFile").get.asInstanceOf[String],
          conf.get("schedoscope.export.ftpUser").get.asInstanceOf[String],
          conf.get("schedoscope.export.ftpPass").getOrElse(null).asInstanceOf[String],
          conf.get("schedoscope.export.ftpEndpoint").get.asInstanceOf[String],
          filePrefix,
          conf.get("schedoscope.export.delimiter").get.asInstanceOf[String],
          conf.get("schedoscope.export.printHeader").get.asInstanceOf[Boolean],
          conf.get("schedoscope.export.passiveMode").get.asInstanceOf[Boolean],
          conf.get("schedoscope.export.userIsRoot").get.asInstanceOf[Boolean],
          conf.get("schedoscope.export.cleanHdfsDir").get.asInstanceOf[Boolean],
          codec,
          fileType
        )

      })
    t.directoriesToDelete = List()
    t.configureWith(
      Map(
        "schedoscope.export.isKerberized" -> isKerberized,
        "schedoscope.export.metastoreUri" -> metastoreUri,
        "schedoscope.export.kerberosPrincipal" -> kerberosPrincipal,
        "schedoscope.export.numReducers" -> numReducers,
        "schedoscope.export.salt" -> exportSalt,
        "schedoscope.export.keyFile" -> keyFile,
        "schedoscope.export.ftpUser" -> ftpUser,
        "schedoscope.export.ftpPass" -> ftpPass,
        "schedoscope.export.ftpEndpoint" -> ftpEndpoint,
        "schedoscope.export.delimiter" -> delimiter,
        "schedoscope.export.printHeader" -> printHeader,
        "schedoscope.export.passiveMode" -> passiveMode,
        "schedoscope.export.userIsRoot" -> userIsRoot,
        "schedoscope.export.cleanHdfsDir" -> cleanHdfsDir))
  }
}
