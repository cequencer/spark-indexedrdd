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

package edu.berkeley.cs.amplab.spark.indexedrdd.impl

import scala.reflect.ClassTag
import scala.collection.JavaConversions._

import org.apache.spark.Logging

import edu.berkeley.cs.amplab.spark.indexedrdd._

import com.ankurdave.part.ArtTree

private[indexedrdd] class PARTPartition[K, V]
    (protected val map: ArtTree)
    (override implicit val kTag: ClassTag[K],
     override implicit val vTag: ClassTag[V],
     implicit val kSer: KeySerializer[K])
  extends IndexedRDDPartition[K, V] with Logging {

  protected def withMap[V2: ClassTag]
      (map: ArtTree): PARTPartition[K, V2] = {
    new PARTPartition(map)
  }

  override def size: Long = map.size()

  override def apply(k: K): V = map.search(kSer.toBytes(k)).asInstanceOf[V]

  override def isDefined(k: K): Boolean = map.search(kSer.toBytes(k)) != null

  override def iterator: Iterator[(K, V)] =
    map.iterator.map(kv => (kSer.fromBytes(kv._1), kv._2.asInstanceOf[V]))

  private def rawIterator: Iterator[(Array[Byte], V)] =
    map.iterator.map(kv => (kv._1, kv._2.asInstanceOf[V]))

  override def multiget(ks: Iterator[K]): Iterator[(K, V)] =
    ks.flatMap { k => Option(this(k)).map(v => (k, v)) }

  override def mapValues[V2: ClassTag](f: (K, V) => V2): IndexedRDDPartition[K, V2] = {
    val newMap = new ArtTree
    for (kv <- rawIterator) newMap.insert(kv._1, f(kSer.fromBytes(kv._1), kv._2))
    this.withMap[V2](newMap)
  }

  override def filter(pred: (K, V) => Boolean): IndexedRDDPartition[K, V] = {
    val newMap = new ArtTree
    for (kv <- rawIterator if pred(kSer.fromBytes(kv._1), kv._2)) {
      newMap.insert(kv._1, kv._2)
    }
    this.withMap[V](newMap)
  }

  override def fullOuterJoin[V2: ClassTag, W: ClassTag]
      (other: IndexedRDDPartition[K, V2])
      (f: (K, Option[V], Option[V2]) => W): IndexedRDDPartition[K, W] = other match {
    case other: PARTPartition[K, V2] =>
      ???

    case _ =>
      fullOuterJoin(other.iterator)(f)
  }

  override def fullOuterJoin[V2: ClassTag, W: ClassTag]
      (other: Iterator[(K, V2)])
      (f: (K, Option[V], Option[V2]) => W): IndexedRDDPartition[K, W] = ???

  override def union[U: ClassTag]
      (other: IndexedRDDPartition[K, U])
      (f: (K, V, U) => V): IndexedRDDPartition[K, V] = other match {
    case other: PARTPartition[K, U] =>
      ???

    case _ =>
      union(other.iterator)(f)
  }

  override def union[U: ClassTag]
      (other: Iterator[(K, U)])
      (f: (K, V, U) => V): IndexedRDDPartition[K, V] = ???

  override def leftOuterJoin[V2: ClassTag, V3: ClassTag]
      (other: IndexedRDDPartition[K, V2])
      (f: (K, V, Option[V2]) => V3): IndexedRDDPartition[K, V3] = other match {
    case other: PARTPartition[K, V2] =>
      // Scan `this` and probe `other`
      val newMap = new ArtTree
      for (kv <- rawIterator) {
        val newV = f(kSer.fromBytes(kv._1), kv._2, Option(other.map.search(kv._1).asInstanceOf[V2]))
        newMap.insert(kv._1, newV)
      }
      this.withMap[V3](newMap)

    case _ =>
      leftOuterJoin(other.iterator)(f)
  }

  override def leftOuterJoin[V2: ClassTag, V3: ClassTag]
      (other: Iterator[(K, V2)])
      (f: (K, V, Option[V2]) => V3): IndexedRDDPartition[K, V3] = ???

  override def innerJoin[U: ClassTag, V2: ClassTag]
      (other: IndexedRDDPartition[K, U])
      (f: (K, V, U) => V2): IndexedRDDPartition[K, V2] = ???

  override def innerJoin[U: ClassTag, V2: ClassTag]
      (other: Iterator[(K, U)])
      (f: (K, V, U) => V2): IndexedRDDPartition[K, V2] = ???
}

private[indexedrdd] object PARTPartition {
  def apply[K: ClassTag, U: ClassTag, V: ClassTag]
      (iter: Iterator[(K, U)], z: U => V, f: (V, U) => V)
      (implicit kSer: KeySerializer[K]): PARTPartition[K, V] = {
    val map = new ArtTree
    iter.foreach { ku =>
      val kBytes = kSer.toBytes(ku._1)
      val oldV = map.search(kBytes).asInstanceOf[V]
      val newV = if (oldV == null) z(ku._2) else f(oldV, ku._2)
      map.insert(kBytes, newV)
    }
    new PARTPartition(map)
  }
}
