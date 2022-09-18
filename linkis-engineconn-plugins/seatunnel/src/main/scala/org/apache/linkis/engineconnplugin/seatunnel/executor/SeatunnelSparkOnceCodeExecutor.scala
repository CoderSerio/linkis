/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.linkis.engineconnplugin.seatunnel.executor

import org.apache.linkis.common.utils.Utils
import org.apache.linkis.engineconn.common.conf.EngineConnConf.ENGINE_CONN_LOCAL_PATH_PWD_KEY
import org.apache.linkis.engineconn.core.EngineConnObject
import org.apache.linkis.engineconn.once.executor.{
  OnceExecutorExecutionContext,
  OperableOnceExecutor
}
import org.apache.linkis.engineconnplugin.seatunnel.client.LinkisSeatunnelSparkClient
import org.apache.linkis.engineconnplugin.seatunnel.client.exception.JobExecutionException
import org.apache.linkis.engineconnplugin.seatunnel.config.{
  SeatunnelEnvConfiguration,
  SeatunnelSparkEnvConfiguration
}
import org.apache.linkis.engineconnplugin.seatunnel.config.SeatunnelSparkEnvConfiguration._
import org.apache.linkis.engineconnplugin.seatunnel.context.SeatunnelEngineConnContext
import org.apache.linkis.engineconnplugin.seatunnel.util.SeatunnelUtils._
import org.apache.linkis.manager.common.entity.resource.{
  CommonNodeResource,
  LoadInstanceResource,
  NodeResource
}
import org.apache.linkis.manager.engineplugin.common.conf.EngineConnPluginConf
import org.apache.linkis.protocol.constants.TaskConstant
import org.apache.linkis.protocol.engine.JobProgressInfo
import org.apache.linkis.scheduler.executer.ErrorExecuteResponse

import org.apache.commons.lang.StringUtils

import java.io.File
import java.nio.file.Files
import java.util
import java.util.concurrent.{Future, TimeUnit}

class SeatunnelSparkOnceCodeExecutor(
    override val id: Long,
    override protected val seatunnelEngineConnContext: SeatunnelEngineConnContext
) extends SeatunnelOnceExecutor
    with OperableOnceExecutor {
  private var params: util.Map[String, String] = _
  private var future: Future[_] = _
  private var daemonThread: Future[_] = _
  var isFailed = false

  override def doSubmit(
      onceExecutorExecutionContext: OnceExecutorExecutionContext,
      options: Map[String, String]
  ): Unit = {
    val code: String = options(TaskConstant.CODE)
    params = onceExecutorExecutionContext.getOnceExecutorContent.getJobContent
      .asInstanceOf[util.Map[String, String]]
    future = Utils.defaultScheduler.submit(new Runnable {
      override def run(): Unit = {
        info("Try to execute codes." + code)
        if (runCode(code) != 0) {
          isFailed = true
          setResponse(
            ErrorExecuteResponse(
              "Run code failed!",
              new JobExecutionException("Exec Seatunnel Spark Code Error")
            )
          )
          tryFailed()
        }
        info("All codes completed, now stop SeatunnelEngineConn.")
        closeDaemon()
        if (!isFailed) {
          trySucceed()
        }
        this synchronized notify()
      }
    })
  }

  protected def runCode(code: String): Int = {
    info("Execute SeatunnelSpark Process")
    val masterKey = LINKIS_SPARK_MASTER.getValue
    val configKey = LINKIS_SPARK_CONFIG.getValue
    val deployModeKey = LINKIS_SPARK_DEPLOY_MODE.getValue

    var args: Array[String] = Array.empty
    if (params != null && StringUtils.isNotBlank(params.get(masterKey))) {
      args = Array(
        GET_LINKIS_SPARK_MASTER,
        params.getOrDefault(masterKey, "yarn"),
        GET_LINKIS_SPARK_DEPLOY_MODE,
        params.getOrDefault(deployModeKey, "client"),
        GET_LINKIS_SPARK_CONFIG,
        generateExecFile(code)
      )
    } else {
      args = localArray(code)
    }

    System.setProperty("SEATUNNEL_HOME", System.getenv(ENGINE_CONN_LOCAL_PATH_PWD_KEY.getValue));
    Files.createSymbolicLink(
      new File(System.getenv(ENGINE_CONN_LOCAL_PATH_PWD_KEY.getValue) + "/seatunnel").toPath,
      new File(SeatunnelEnvConfiguration.SEATUNNEL_HOME.getValue).toPath
    )
    info(s"Execute SeatunnelSpark Process end")
    LinkisSeatunnelSparkClient.main(args)
  }

  override protected def waitToRunning(): Unit = {
    if (!isCompleted)
      daemonThread = Utils.defaultScheduler.scheduleAtFixedRate(
        new Runnable {
          override def run(): Unit = {
            if (!(future.isDone || future.isCancelled)) {
              info("The Seatunnel Spark Process In Running")
            }
          }
        },
        SeatunnelEnvConfiguration.SEATUNNEL_STATUS_FETCH_INTERVAL.getValue.toLong,
        SeatunnelEnvConfiguration.SEATUNNEL_STATUS_FETCH_INTERVAL.getValue.toLong,
        TimeUnit.MILLISECONDS
      )
  }

  override def getCurrentNodeResource(): NodeResource = {
    val properties = EngineConnObject.getEngineCreationContext.getOptions
    if (properties.containsKey(EngineConnPluginConf.JAVA_ENGINE_REQUEST_MEMORY.key)) {
      val settingClientMemory = properties.get(EngineConnPluginConf.JAVA_ENGINE_REQUEST_MEMORY.key)
      if (!settingClientMemory.toLowerCase().endsWith("g")) {
        properties.put(
          EngineConnPluginConf.JAVA_ENGINE_REQUEST_MEMORY.key,
          settingClientMemory + "g"
        )
      }
    }
    val actualUsedResource = new LoadInstanceResource(
      EngineConnPluginConf.JAVA_ENGINE_REQUEST_MEMORY.getValue(properties).toLong,
      EngineConnPluginConf.JAVA_ENGINE_REQUEST_CORES.getValue(properties),
      EngineConnPluginConf.JAVA_ENGINE_REQUEST_INSTANCE
    )
    val resource = new CommonNodeResource
    resource.setUsedResource(actualUsedResource)
    resource
  }

  protected def closeDaemon(): Unit = {
    if (daemonThread != null) daemonThread.cancel(true)
  }

  override def getProgress: Float = 0f

  override def getProgressInfo: Array[JobProgressInfo] = {
    Array.empty[JobProgressInfo]
  }

  override def getMetrics: util.Map[String, Any] = {
    new util.HashMap[String, Any]()
  }

  override def getDiagnosis: util.Map[String, Any] = new util.HashMap[String, Any]()
}
