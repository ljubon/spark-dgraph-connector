package uk.co.gresearch.spark.dgraph.connector.partitioner

import java.math.BigInteger
import java.security.MessageDigest

import uk.co.gresearch.spark.dgraph.connector.{ClusterState, Partition, Predicate, Schema}

import scala.language.implicitConversions

class PredicatePartitioner(schema: Schema, clusterState: ClusterState, predicatesPerPartition: Int)
  extends Partitioner {

  if (predicatesPerPartition <= 0)
    throw new IllegalArgumentException(s"predicatesPerPartition must be larger than zero: $predicatesPerPartition")

  def getPartitionsForPredicates(predicates: Set[_]): Int =
    if (predicates.isEmpty) 1 else 1 + (predicates.size-1) / predicatesPerPartition

  override def getPartitions: Seq[Partition] = {
    val partitionsPerGroup = clusterState.groupPredicates.mapValues(getPartitionsForPredicates)
    PredicatePartitioner.getPartitions(schema, clusterState, partitionsPerGroup)
  }

}

object PredicatePartitioner extends ClusterStateHelper {

  val md5: MessageDigest = MessageDigest.getInstance("MD5")

  /**
   * Compute MD5 hash of predicate name. Hash is a BigInt.
   * @param predicate predicate
   * @return BigInt hash
   */
  def hash(predicate: Predicate): BigInt = {
    val digest = md5.digest(predicate.predicateName.getBytes)
    new BigInteger(1,digest)
  }

  /**
   * Shards a set of predicates based on the MD5 hash. Shards are probably even-sized,
   * but this is not guaranteed.
   * @param predicates set of predicates
   * @param shards number of shards
   * @return predicates shard
   */
  def shard(predicates: Set[Predicate], shards: Int): Seq[Set[Predicate]] = {
    if (shards < 1) throw new IllegalArgumentException(s"shards must be larger than zero: $shards")
    predicates.groupBy(hash(_) % shards).values.toSeq
  }

  /**
   * Partitions a set of predicates in equi-sized partitions. Predicates get sorted by MD5 hash and
   * then round-robin assigned to partitions.
   * @param predicates set of predicates
   * @param partitions number of partitions
   * @return partitions
   */
  def partition(predicates: Set[Predicate], partitions: Int): Seq[Set[Predicate]] = {
    if (partitions < 1)
      throw new IllegalArgumentException(s"partitions must be larger than zero: $partitions")

    predicates
      // turn into seq and sort by hash (consistently randomize)
      .toSeq.sortBy(hash)
      // add index to predicates
      .zipWithIndex
      // group by predicate index % partitions
      .groupBy(_._2 % partitions)
      // sort by partition id
      .toSeq.sortBy(_._1)
      // drop keys and remove index from (predicate, index) tuple, restore set
      .map(_._2.map(_._1).toSet)
  }

  def getPartitions(schema: Schema, clusterState: ClusterState, partitionsInGroup: (String) => Int): Seq[Partition] =
    clusterState.groupPredicates.keys.flatMap { group =>
      val targets = getGroupTargets(clusterState, group).toSeq
      val partitions = partitionsInGroup(group)
      val groupPredicates = getGroupPredicates(clusterState, group, schema)
      val predicatesPartitions = partition(groupPredicates, partitions)

      predicatesPartitions.indices.map { index =>
        Partition(targets.rotate(index), Some(predicatesPartitions(index)))
      }
    }.toSeq

  implicit class RotatingSeq[T](seq: Seq[T]) {
    @scala.annotation.tailrec
    final def rotate(i: Int): Seq[T] =
      if (seq.isEmpty)
        seq
      else if (i >= 0 && i < seq.size)
        seq.drop(i) ++ seq.take(i)
      else
        rotate(if (i < 0) i + seq.size else i - seq.size)
  }

}