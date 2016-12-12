/**
 *  Copyright (c) 2016 Intel Corporation 
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.trustedanalytics.daaltk.models.regression.linear_regression

import com.intel.daal.algorithms.linear_regression.Model
import com.intel.daal.algorithms.linear_regression.prediction._
import com.intel.daal.services.DaalContext
import org.trustedanalytics.sparktk.frame.internal.rdd.FrameRdd
import org.apache.spark.sql
import org.trustedanalytics.sparktk.frame.{ Column, DataTypes, FrameSchema }
import org.trustedanalytics.daaltk.DaalUtils._
import org.trustedanalytics.daaltk.models.tables.{ DistributedNumericTable, IndexedNumericTable }
import com.intel.daal.algorithms.ModelSerializer

object LinearPredictAlgorithm extends Serializable {
  val PredictColumnPrefix = "predict_" //Prefix to prepend to prediction column
}

import LinearPredictAlgorithm._

/**
 * Algorithm for computing predictions of linear regression model using QR decomposition
 *
 * @param serializedModel Serialized linear regression model
 * @param frameRdd Frame with test data
 * @param valueColumn Column name containing the value for each observation.
 * @param observationColumns List of column(s) storing the observations
 */
case class LinearPredictAlgorithm(serializedModel: List[Byte],
                                  frameRdd: FrameRdd,
                                  valueColumn: String,
                                  observationColumns: Seq[String]) {
  /**
   * Predict linear regression model using QR decomposition
   *
   * @return Frame with predictions for linear model
   */
  def predict(): FrameRdd = {

    val distributedTable = DistributedNumericTable.createTable(frameRdd, observationColumns)
    val predictRdd = distributedTable.rdd.flatMap(testData => {
      if (testData.isEmpty) {
        List.empty[sql.Row].iterator
      }
      else {
        withDaalContext { context =>
          val trainedModel = ModelSerializer.deserializeQrModel(context, serializedModel.toArray)
          val predictions = predictTableResults(context, trainedModel, testData)
          predictions.toRowIter(context)
        }.elseError("Could not predict linear regression model")
      }
    })

    val predictColumns = List(Column(PredictColumnPrefix + valueColumn, DataTypes.float64))
    frameRdd.zipFrameRdd(new FrameRdd(FrameSchema(predictColumns), predictRdd))
  }

  /**
   * Predict results of linear regression model using QR decomposition for input table
   *
   * @param context DAAL context
   * @param trainedModel Trained linear model
   * @param testData Input table with test data
   *
   * @return Output table with predictions
   */
  private def predictTableResults(context: DaalContext,
                                  trainedModel: Model,
                                  testData: IndexedNumericTable): IndexedNumericTable = {
    val predictAlgorithm = new PredictionBatch(context, classOf[java.lang.Double], PredictionMethod.defaultDense)
    val testTable = testData.getUnpackedTable(context)

    require(testTable.getNumberOfColumns > 0 && testTable.getNumberOfRows > 0)
    predictAlgorithm.input.set(PredictionInputId.data, testTable)
    predictAlgorithm.input.set(PredictionInputId.model, trainedModel)

    /* Compute and retrieve prediction results */
    val partialResult = predictAlgorithm.compute()

    val predictions = partialResult.get(PredictionResultId.prediction)
    new IndexedNumericTable(testData.index, predictions)
  }
}
