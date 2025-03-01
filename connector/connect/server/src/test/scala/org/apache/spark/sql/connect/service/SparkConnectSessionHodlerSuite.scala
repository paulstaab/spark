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

package org.apache.spark.sql.connect.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.sys.process.Process

import com.google.common.collect.Lists
import org.scalatest.time.SpanSugar._

import org.apache.spark.api.python.SimplePythonFunction
import org.apache.spark.sql.IntegratedUDFTestUtils
import org.apache.spark.sql.connect.common.InvalidPlanInput
import org.apache.spark.sql.connect.planner.{PythonStreamingQueryListener, StreamingForeachBatchHelper}
import org.apache.spark.sql.test.SharedSparkSession

class SparkConnectSessionHolderSuite extends SharedSparkSession {

  test("DataFrame cache: Successful put and get") {
    val sessionHolder = SessionHolder.forTesting(spark)
    import sessionHolder.session.implicits._

    val data1 = Seq(("k1", "v1"), ("k2", "v2"), ("k3", "v3"))
    val df1 = data1.toDF()
    val id1 = "df_id_1"
    sessionHolder.cacheDataFrameById(id1, df1)

    val expectedDf1 = sessionHolder.getDataFrameOrThrow(id1)
    assert(expectedDf1 == df1)

    val data2 = Seq(("k4", "v4"), ("k5", "v5"))
    val df2 = data2.toDF()
    val id2 = "df_id_2"
    sessionHolder.cacheDataFrameById(id2, df2)

    val expectedDf2 = sessionHolder.getDataFrameOrThrow(id2)
    assert(expectedDf2 == df2)
  }

  test("DataFrame cache: Should throw when dataframe is not found") {
    val sessionHolder = SessionHolder.forTesting(spark)
    import sessionHolder.session.implicits._

    val key1 = "key_1"

    assertThrows[InvalidPlanInput] {
      sessionHolder.getDataFrameOrThrow(key1)
    }

    val data1 = Seq(("k1", "v1"), ("k2", "v2"), ("k3", "v3"))
    val df1 = data1.toDF()
    sessionHolder.cacheDataFrameById(key1, df1)
    sessionHolder.getDataFrameOrThrow(key1)

    val key2 = "key_2"
    assertThrows[InvalidPlanInput] {
      sessionHolder.getDataFrameOrThrow(key2)
    }
  }

  test("DataFrame cache: Remove cache and then get should fail") {
    val sessionHolder = SessionHolder.forTesting(spark)
    import sessionHolder.session.implicits._

    val key1 = "key_1"
    val data1 = Seq(("k1", "v1"), ("k2", "v2"), ("k3", "v3"))
    val df1 = data1.toDF()
    sessionHolder.cacheDataFrameById(key1, df1)
    sessionHolder.getDataFrameOrThrow(key1)

    sessionHolder.removeCachedDataFrame(key1)
    assertThrows[InvalidPlanInput] {
      sessionHolder.getDataFrameOrThrow(key1)
    }
  }

  private def streamingForeachBatchFunction(pysparkPythonPath: String): Array[Byte] = {
    var binaryFunc: Array[Byte] = null
    withTempPath { path =>
      Process(
        Seq(
          IntegratedUDFTestUtils.pythonExec,
          "-c",
          "from pyspark.serializers import CloudPickleSerializer; " +
            s"f = open('$path', 'wb');" +
            "f.write(CloudPickleSerializer().dumps((" +
            "lambda df, batchId: batchId)))"),
        None,
        "PYTHONPATH" -> pysparkPythonPath).!!
      binaryFunc = Files.readAllBytes(path.toPath)
    }
    assert(binaryFunc != null)
    binaryFunc
  }

  private def streamingQueryListenerFunction(pysparkPythonPath: String): Array[Byte] = {
    var binaryFunc: Array[Byte] = null
    val pythonScript =
      """
        |from pyspark.sql.streaming.listener import StreamingQueryListener
        |
        |class MyListener(StreamingQueryListener):
        |    def onQueryStarted(e):
        |        pass
        |
        |    def onQueryIdle(e):
        |        pass
        |
        |    def onQueryProgress(e):
        |        pass
        |
        |    def onQueryTerminated(e):
        |        pass
        |
        |listener = MyListener()
      """.stripMargin
    withTempPath { codePath =>
      Files.write(codePath.toPath, pythonScript.getBytes(StandardCharsets.UTF_8))
      withTempPath { path =>
        Process(
          Seq(
            IntegratedUDFTestUtils.pythonExec,
            "-c",
            "from pyspark.serializers import CloudPickleSerializer; " +
              s"f = open('$path', 'wb');" +
              s"exec(open('$codePath', 'r').read());" +
              "f.write(CloudPickleSerializer().dumps(listener))"),
          None,
          "PYTHONPATH" -> pysparkPythonPath).!!
        binaryFunc = Files.readAllBytes(path.toPath)
      }
    }
    assert(binaryFunc != null)
    binaryFunc
  }

  private def dummyPythonFunction(sessionHolder: SessionHolder)(
      fcn: String => Array[Byte]): SimplePythonFunction = {
    val sparkPythonPath =
      s"${IntegratedUDFTestUtils.pysparkPythonPath}:${IntegratedUDFTestUtils.pythonPath}"

    SimplePythonFunction(
      command = fcn(sparkPythonPath),
      envVars = mutable.Map("PYTHONPATH" -> sparkPythonPath).asJava,
      pythonIncludes = sessionHolder.artifactManager.getSparkConnectPythonIncludes.asJava,
      pythonExec = IntegratedUDFTestUtils.pythonExec,
      pythonVer = IntegratedUDFTestUtils.pythonVer,
      broadcastVars = Lists.newArrayList(),
      accumulator = null)
  }

  test("python foreachBatch process: process terminates after query is stopped") {
    // scalastyle:off assume
    assume(IntegratedUDFTestUtils.shouldTestPandasUDFs)
    // scalastyle:on assume

    val sessionHolder = SessionHolder.forTesting(spark)
    try {
      SparkConnectService.start(spark.sparkContext)

      val pythonFn = dummyPythonFunction(sessionHolder)(streamingForeachBatchFunction)
      val (fn1, cleaner1) =
        StreamingForeachBatchHelper.pythonForeachBatchWrapper(pythonFn, sessionHolder)
      val (fn2, cleaner2) =
        StreamingForeachBatchHelper.pythonForeachBatchWrapper(pythonFn, sessionHolder)

      val query1 = spark.readStream
        .format("rate")
        .load()
        .writeStream
        .format("memory")
        .queryName("foreachBatch_termination_test_q1")
        .foreachBatch(fn1)
        .start()

      val query2 = spark.readStream
        .format("rate")
        .load()
        .writeStream
        .format("memory")
        .queryName("foreachBatch_termination_test_q2")
        .foreachBatch(fn2)
        .start()

      sessionHolder.streamingForeachBatchRunnerCleanerCache
        .registerCleanerForQuery(query1, cleaner1)
      sessionHolder.streamingForeachBatchRunnerCleanerCache
        .registerCleanerForQuery(query2, cleaner2)

      val (runner1, runner2) = (cleaner1.runner, cleaner2.runner)

      // assert both python processes are running
      assert(!runner1.isWorkerStopped().get)
      assert(!runner2.isWorkerStopped().get)
      // stop query1
      query1.stop()
      // assert query1's python process is not running
      eventually(timeout(30.seconds)) {
        assert(runner1.isWorkerStopped().get)
        assert(!runner2.isWorkerStopped().get)
      }

      // stop query2
      query2.stop()
      eventually(timeout(30.seconds)) {
        // assert query2's python process is not running
        assert(runner2.isWorkerStopped().get)
      }

      assert(spark.streams.active.isEmpty) // no running query
      assert(spark.streams.listListeners().length == 1) // only process termination listener
    } finally {
      SparkConnectService.stop()
      // remove process termination listener
      spark.streams.listListeners().foreach(spark.streams.removeListener)
    }
  }

  test("python listener process: process terminates after listener is removed") {
    // scalastyle:off assume
    assume(IntegratedUDFTestUtils.shouldTestPandasUDFs)
    // scalastyle:on assume

    val sessionHolder = SessionHolder.forTesting(spark)
    try {
      SparkConnectService.start(spark.sparkContext)

      val pythonFn = dummyPythonFunction(sessionHolder)(streamingQueryListenerFunction)

      val id1 = "listener_removeListener_test_1"
      val id2 = "listener_removeListener_test_2"
      val listener1 = new PythonStreamingQueryListener(pythonFn, sessionHolder)
      val listener2 = new PythonStreamingQueryListener(pythonFn, sessionHolder)

      sessionHolder.cacheListenerById(id1, listener1)
      spark.streams.addListener(listener1)
      sessionHolder.cacheListenerById(id2, listener2)
      spark.streams.addListener(listener2)

      val (runner1, runner2) = (listener1.runner, listener2.runner)

      // assert both python processes are running
      assert(!runner1.isWorkerStopped().get)
      assert(!runner2.isWorkerStopped().get)

      // remove listener1
      spark.streams.removeListener(listener1)
      sessionHolder.removeCachedListener(id1)
      // assert listener1's python process is not running
      eventually(timeout(30.seconds)) {
        assert(runner1.isWorkerStopped().get)
        assert(!runner2.isWorkerStopped().get)
      }

      // remove listener2
      spark.streams.removeListener(listener2)
      sessionHolder.removeCachedListener(id2)
      eventually(timeout(30.seconds)) {
        // assert listener2's python process is not running
        assert(runner2.isWorkerStopped().get)
        // all listeners are removed
        assert(spark.streams.listListeners().isEmpty)
      }
    } finally {
      SparkConnectService.stop()
    }
  }
}
