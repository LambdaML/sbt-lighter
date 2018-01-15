/*
 * Copyright 2017 Pishen Tsai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbtemrspark

import com.amazonaws.services.elasticmapreduce.model.{
  Unit => _,
  Command => _,
  _
}
import com.amazonaws.services.elasticmapreduce.{
  AmazonElasticMapReduce,
  AmazonElasticMapReduceClientBuilder
}
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.PutObjectRequest
import com.typesafe.scalalogging.StrictLogging
import sbt.Defaults.runMainParser
import sbt.Keys._
import sbt._
import sbt.complete.DefaultParsers._
import sbtassembly.AssemblyKeys._
import sbtassembly.AssemblyPlugin
import sjsonnew.BasicJsonProtocol._

import scala.collection.JavaConverters._
import scala.concurrent.duration._

object EmrSparkPlugin extends AutoPlugin with StrictLogging {
  object autoImport {
    //configs
    val sparkClusterName =
      settingKey[String]("emr cluster's name")
    val sparkClusterId =
      settingKey[Option[String]]("emr cluster's id")
    val sparkAwsRegion =
      settingKey[String]("aws's region")
    val sparkEmrRelease =
      settingKey[String]("emr's release label")
    val sparkEmrServiceRole =
      settingKey[String]("emr's service role")
    val sparkEmrConfigs =
      settingKey[Seq[EmrConfig]]("emr configurations")
    val sparkEmrApplications =
      settingKey[Seq[String]]("emr applications")
    val sparkSubnetId =
      settingKey[Option[String]]("spark's subnet id")
    val sparkSecurityGroupIds =
      settingKey[Seq[String]](
        "additional security group ids for both master and slave ec2 instances"
      )
    val sparkInstanceCount =
      settingKey[Int]("total number of instances")
    val sparkInstanceType =
      settingKey[String]("spark nodes' instance type")
    val sparkInstanceBidPrice =
      settingKey[Option[Double]]("spark nodes' bid price")
    val sparkInstanceRole =
      settingKey[String]("spark ec2 instance's role")
    val sparkInstanceKeyName =
      settingKey[Option[String]]("instance's key name")
    val sparkS3JarFolder =
      settingKey[String]("S3 folder for putting the executable jar")
    val sparkS3LogUri =
      settingKey[Option[String]]("S3 folder for putting the EMR logs")
    val sparkTimeoutDuration =
      settingKey[Duration]("timeout duration for sparkTimeout")
    val sparkSubmitConfs =
      settingKey[Map[String, String]](
        "The configs of --conf when running spark-submit"
      )

    //underlying configs
    val sparkEmrClientBuilder =
      settingKey[AmazonElasticMapReduceClientBuilder](
        "default EMR client builder"
      )
    val sparkS3ClientBuilder =
      settingKey[AmazonS3ClientBuilder]("default S3 client builder")
    val sparkS3PutObjectDecorator =
      settingKey[PutObjectRequest => PutObjectRequest](
        "Allow user to set metadata with put request.Like server side encryption"
      )
    val sparkJobFlowInstancesConfig =
      settingKey[JobFlowInstancesConfig]("default JobFlowInstancesConfig")
    val sparkRunJobFlowRequest =
      settingKey[RunJobFlowRequest]("default RunJobFlowRequest")

    //commands
    val sparkListClusters =
      taskKey[Unit]("list existing active clusters")
    val sparkSubmitJob =
      inputKey[Unit]("submit the job")
    val sparkSubmitJobWithMain =
      inputKey[Unit]("submit the job with specified main class")
    val sparkMonitor =
      taskKey[Unit]("monitor and terminate the cluster when timeout")
  }
  import autoImport._

  override def trigger = allRequirements
  override def requires = AssemblyPlugin

  val activatedClusterStates = Seq(
    ClusterState.RUNNING,
    ClusterState.STARTING,
    ClusterState.WAITING,
    ClusterState.BOOTSTRAPPING
  )

  override lazy val projectSettings = baseSettings

  lazy val baseSettings = Seq(
    sparkClusterName := name.value,
    sparkClusterId := None,
    sparkAwsRegion := "changeme",
    sparkEmrRelease := "emr-5.11.0",
    sparkEmrServiceRole := "EMR_DefaultRole",
    sparkEmrConfigs := Seq.empty,
    sparkEmrApplications := Seq("Spark"),
    sparkSubnetId := None,
    sparkSecurityGroupIds := Seq.empty,
    sparkInstanceCount := 1,
    sparkInstanceType := "m3.xlarge",
    sparkInstanceBidPrice := None,
    sparkInstanceRole := "EMR_EC2_DefaultRole",
    sparkInstanceKeyName := None,
    sparkTimeoutDuration := 90.minutes,
    sparkS3JarFolder := "changeme",
    sparkS3LogUri := None,
    sparkS3PutObjectDecorator := identity,
    sparkSubmitConfs := Map.empty,
    sparkEmrClientBuilder := {
      AmazonElasticMapReduceClientBuilder.standard
        .withRegion(sparkAwsRegion.value)
    },
    sparkS3ClientBuilder := {
      AmazonS3ClientBuilder.standard
        .withRegion(sparkAwsRegion.value)
    },
    sparkJobFlowInstancesConfig := {
      Some(new JobFlowInstancesConfig())
        .map(c => sparkSubnetId.value.fold(c)(id => c.withEc2SubnetId(id)))
        .map(
          c => sparkInstanceKeyName.value.fold(c)(id => c.withEc2KeyName(id))
        )
        .map { c =>
          val ids = sparkSecurityGroupIds.value
          if (ids.nonEmpty) {
            c.withAdditionalMasterSecurityGroups(ids: _*)
              .withAdditionalSlaveSecurityGroups(ids: _*)
          } else c
        }
        .get
        .withInstanceGroups {
          val masterConfig = Some(new InstanceGroupConfig())
            .map { c =>
              sparkInstanceBidPrice.value
                .map(_.toString)
                .fold {
                  c.withMarket(MarketType.ON_DEMAND)
                } {
                  c.withMarket(MarketType.SPOT).withBidPrice
                }
            }
            .get
            .withInstanceCount(1)
            .withInstanceRole(InstanceRoleType.MASTER)
            .withInstanceType(sparkInstanceType.value)

          val slaveCount = sparkInstanceCount.value - 1
          val slaveConfig = Some(new InstanceGroupConfig())
            .map { c =>
              sparkInstanceBidPrice.value
                .map(_.toString)
                .fold {
                  c.withMarket(MarketType.ON_DEMAND)
                } {
                  c.withMarket(MarketType.SPOT).withBidPrice
                }
            }
            .get
            .withInstanceCount(slaveCount)
            .withInstanceRole(InstanceRoleType.CORE)
            .withInstanceType(sparkInstanceType.value)

          if (slaveCount <= 0) {
            Seq(masterConfig).asJava
          } else {
            Seq(masterConfig, slaveConfig).asJava
          }
        }
        .withKeepJobFlowAliveWhenNoSteps(true)
    },
    sparkRunJobFlowRequest := {
      Some(new RunJobFlowRequest())
        .map { r =>
          val emrConfigs = sparkEmrConfigs.value
          if (emrConfigs.nonEmpty) {
            r.withConfigurations(emrConfigs.map(_.toAwsEmrConfig()): _*)
          } else r
        }
        .map { r =>
          sparkS3LogUri.value.map(r.withLogUri).getOrElse(r)
        }
        .get
        .withName(sparkClusterName.value)
        .withApplications(
          sparkEmrApplications.value.map(a => new Application().withName(a)): _*
        )
        .withReleaseLabel(sparkEmrRelease.value)
        .withServiceRole(sparkEmrServiceRole.value)
        .withJobFlowRole(sparkInstanceRole.value)
        .withInstances(sparkJobFlowInstancesConfig.value)
    },
    sparkSubmitJob := {
      Def.inputTaskDyn {
        implicit val emr = sparkEmrClientBuilder.value.build()
        val args = spaceDelimited("<arg>").parsed
        val mainClassValue = (mainClass in Compile).value.getOrElse(
          sys.error("Can't locate the main class in your application.")
        )
        submitJob(mainClassValue, args, sparkSubmitConfs.value)
      }.evaluated
    },
    sparkSubmitJobWithMain := {
      Def.inputTaskDyn {
        implicit val emr = sparkEmrClientBuilder.value.build()
        val (mainClass, args) =
          loadForParser(discoveredMainClasses in Compile) { (s, names) =>
            runMainParser(s, names getOrElse Nil)
          }.parsed
        submitJob(mainClass, args, sparkSubmitConfs.value)
      }.evaluated
    },
    sparkListClusters := {
      val clusters = clusterMap.value().values
      if (clusters.isEmpty) {
        logger.info("No active cluster found.")
      } else {
        logger.info(s"${clusters.size} active clusters found: ")
        clusters.foreach { c =>
          logger.info(s"Id: ${c.getId} | Name: ${c.getName}")
        }
      }
    },
    sparkMonitor := {
      implicit val emr = sparkEmrClientBuilder.value.build()

      findClusterWithName(sparkClusterName.value) match {
        case None =>
          logger.info(
            s"The cluster with name ${sparkClusterName.value} does not exist."
          )
        case Some(cluster) =>
          logger.info(s"Found cluster ${cluster.getId}, start monitoring.")
          val timeoutTime = System.currentTimeMillis() +
            sparkTimeoutDuration.value.toMillis
          def checkStatus(): Unit = {
            print(".")
            val updatedCluster = emr.describeCluster {
              new DescribeClusterRequest().withClusterId(cluster.getId)
            }.getCluster
            val state = updatedCluster.getStatus.getState
            val timeout = System.currentTimeMillis() >= timeoutTime
            val activated =
              activatedClusterStates.map(_.toString).contains(state)
            if (timeout && activated) {
              emr.terminateJobFlows {
                new TerminateJobFlowsRequest().withJobFlowIds(cluster.getId)
              }
              println()
              sys.error("Timeout. Cluster terminated.")
            } else if (!activated) {
              val hasAbnormalStep = emr
                .listSteps(new ListStepsRequest().withClusterId(cluster.getId))
                .getSteps
                .asScala
                .map(_.getStatus.getState)
                .exists(_ != StepState.COMPLETED.toString)
              if (hasAbnormalStep) {
                println()
                sys.error("Cluster terminated with abnormal step.")
              } else {
                println()
                logger.info("Cluster terminated without error.")
              }
            } else {
              Thread.sleep(5000)
              checkStatus()
            }
          }
          checkStatus()
      }
    },
    shellPrompt := { state =>
      Project
        .extract(state)
        .get(sparkClusterId)
        .map("[\u001B[36m" + _ + "\u001B[0m]> ")
        .getOrElse("> ")
    },
    commands ++= Seq(
      Command.command("sparkCreateCluster") { state =>
        val emr = sparkEmrClientBuilder.value.build
        val res = emr.runJobFlow(sparkRunJobFlowRequest.value)
        logger.info(
          s"Your new cluster's id is ${res.getJobFlowId}, you may check its status on AWS console."
        )
        Project
          .extract(state)
          .append(
            Seq(sparkClusterId := Some(res.getJobFlowId)),
            state
          )
      },
      Command("sparkBindCluster") { state =>
        Space ~> oneOf(
          clusterMap.value().keys.toSeq.map(literal)
        )
      } { case (state, clusterId) =>
        Project
          .extract(state)
          .append(
            Seq(sparkClusterId := Some(clusterId)),
            state
          )
      },
      Command.command("sparkTerminateCluster") { state =>
        sparkClusterId.value match {
          case None =>
            logger.info(
              "sparkClusterId is None, please specify the cluster you want to terminate using sparkBindToCluster first."
            )
          case Some(clusterId) =>
            val emr = sparkEmrClientBuilder.value.build
            emr.terminateJobFlows(
              new TerminateJobFlowsRequest().withJobFlowIds(clusterId)
            )
            logger.info(
              s"Cluster with id ${clusterId} is terminating, please check aws console for the following information."
            )
        }
        Project
          .extract(state)
          .append(
            Seq(sparkClusterId := None),
            state
          )
      }
    )
  )

  def submitJob(
      mainClass: String,
      args: Seq[String],
      sparkConfs: Map[String, String]
  )(implicit emr: AmazonElasticMapReduce) = Def.task {
    val jar = assembly.value
    val s3Jar = new S3Url(sparkS3JarFolder.value) / jar.getName
    logger.info(s"Putting ${jar.getPath} to ${s3Jar.toString}")

    val putRequest = sparkS3PutObjectDecorator.value(
      new PutObjectRequest(s3Jar.bucket, s3Jar.key, jar)
    )
    sparkS3ClientBuilder.value.build().putObject(putRequest)

    val sparkSubmitArgs = Seq(
      "spark-submit",
      "--deploy-mode",
      "cluster",
      "--class",
      mainClass
    ) ++ sparkConfs.toSeq.flatMap {
      case (k, v) => Seq("--conf", k + "=" + v)
    } ++ (s3Jar.toString +: args)

    val step = new StepConfig()
      .withActionOnFailure(ActionOnFailure.CONTINUE)
      .withName("Spark Step")
      .withHadoopJarStep(
        new HadoopJarStepConfig()
          .withJar("command-runner.jar")
          .withArgs(sparkSubmitArgs.asJava)
      )

    findClusterWithName(sparkClusterName.value) match {
      case Some(cluster) =>
        emr.addJobFlowSteps(
          new AddJobFlowStepsRequest()
            .withJobFlowId(cluster.getId)
            .withSteps(step)
        )
        logger.info(
          s"Your job is added to the cluster with id ${cluster.getId}, you may check its status on AWS console."
        )
      case None =>
        val jobFlowRequest = sparkRunJobFlowRequest.value
          .withSteps(
            (sparkRunJobFlowRequest.value.getSteps.asScala :+ step): _*
          )
          .withInstances(
            sparkJobFlowInstancesConfig.value
              .withKeepJobFlowAliveWhenNoSteps(false)
          )
        val res = emr.runJobFlow(jobFlowRequest)
        logger.info(
          s"Your new cluster's id is ${res.getJobFlowId}, you may check its status on AWS console."
        )
    }
  }

  lazy val clusterMap = Def.setting {
    () => sparkEmrClientBuilder
      .value
      .build
      .listClusters(
        new ListClustersRequest().withClusterStates(
          ClusterState.RUNNING,
          ClusterState.STARTING,
          ClusterState.WAITING,
          ClusterState.BOOTSTRAPPING
        )
      )
      .getClusters
      .asScala
      .map(cluster => cluster.getId -> cluster)
      .toMap
  }

  def findClusterWithName(
      name: String
  )(implicit emr: AmazonElasticMapReduce) = {
    emr
      .listClusters {
        new ListClustersRequest().withClusterStates(activatedClusterStates: _*)
      }
      .getClusters()
      .asScala
      .find(_.getName == name)
  }
}
