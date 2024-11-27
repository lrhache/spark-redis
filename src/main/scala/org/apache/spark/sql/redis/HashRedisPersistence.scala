package org.apache.spark.sql.redis

import java.util.{List => JList}

import com.redislabs.provider.redis.util.ParseUtils
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types._
import redis.clients.jedis.Pipeline

import scala.collection.JavaConverters._

/**
  * @author The Viet Nguyen
  */
class HashRedisPersistence extends RedisPersistence[Any] {

  override def save(pipeline: Pipeline, key: String, value: Any, ttl: Int): Unit = {
    val javaValue = value.asInstanceOf[Map[String, String]].asJava
    pipeline.hmset(key, javaValue)
    if (ttl > 0) {
      pipeline.expire(key, ttl.toLong)
    }
  }

  override def load(pipeline: Pipeline, key: String, requiredColumns: Seq[String]): Unit = {
    pipeline.hmget(key, requiredColumns: _*)
  }

  override def encodeRow(
    keyName: String,
    value: Row,
    structColumnName: Option[String] = None // Optional parameter for the struct column
  ): Map[String, String] = {
    val kvMap: Map[String, Any] = structColumnName match {
      case Some(structColName) =>
        // use the struct's key-values from the specified column
        val structValue = value.getAs[Row](structColName)
        if (structValue != null) {
          val structFields = structValue.schema.fields.map(_.name)
          structValue.getValuesMap[Any](structFields)
        } else {
          // ff the struct is null, return an empty map
          Map.empty[String, Any]
        }
      case None =>
        // Use the row's values as before
        val fields = value.schema.fields.map(_.name)
        value.getValuesMap[Any](fields)
    }

    kvMap
      .filter { case (_, v) =>
        // don't store null values
        v != null
      }
      .filter { case (k, _) =>
        // don't store key values
        k != keyName
      }
      .map { case (k, v) =>
        k -> String.valueOf(v)
      }
  }


  override def decodeRow(keyMap: (String, String), value: Any, schema: StructType,
                         requiredColumns: Seq[String]): Row = {
    val scalaValue = value.asInstanceOf[JList[String]].asScala
    val values = requiredColumns.zip(scalaValue)
    val results = values :+ keyMap
    val fieldsValue = ParseUtils.parseFields(results.toMap, schema)
    new GenericRowWithSchema(fieldsValue, schema)
  }
}
