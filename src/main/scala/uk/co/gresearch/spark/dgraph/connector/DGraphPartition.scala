package uk.co.gresearch.spark.dgraph.connector

import io.dgraph.DgraphClient
import io.dgraph.DgraphProto.Response
import io.grpc.ManagedChannel
import org.apache.spark.sql.connector.read.InputPartition

case class DGraphPartition(targets: Seq[Target]) extends InputPartition {

  private val query: String =
    """{
      |  nodes (func: has(dgraph.type)) {
      |    uid
      |    expand(_all_) {
      |      uid
      |    }
      |  }
      |}""".stripMargin

  // TODO: use host names of DGraph alphas to co-locate partitions
  override def preferredLocations(): Array[String] = super.preferredLocations()

  /**
   * Reads the entire partition and returns all triples.
   * @return triples
   */
  def getTriples: Iterator[Triple] = {
    val channels: Seq[ManagedChannel] = targets.map(toChannel)
    try {
      val client: DgraphClient = getClientFromChannel(channels)
      val response: Response = client.newReadOnlyTransaction().query(query)
      val json: String = response.getJson.toStringUtf8
      TriplesFactory.fromJson(json)
    } finally {
      channels.foreach(_.shutdown())
    }
  }

}