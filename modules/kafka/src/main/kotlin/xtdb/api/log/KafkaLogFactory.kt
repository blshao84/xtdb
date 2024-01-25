@file:UseSerializers(DurationSerde::class)
package xtdb.api.log

import clojure.lang.IFn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xtdb.DurationSerde
import xtdb.api.*
import xtdb.api.module.ModuleRegistration
import xtdb.api.module.ModuleRegistry
import xtdb.util.requiringResolve
import java.nio.file.Path
import java.time.Duration

/**
 * Used to set configuration options for Kafka as an XTDB Transaction Log.
 *
 * For more info on setting up the necessary infrastructure to be able to use Kafka as an XTDB Transaction Log, see the
 * section on infrastructure within our [Kafka Module Reference](https://docs.xtdb.com/components/tx-log/kafka.html).
 *
 * Example usage, as part of a node config:
 * ```kotlin
 * Xtdb.Config(
 *    log = KafkaLogFactory(
 *              bootstrapServers = "localhost:9092",
 *              topicName = "xtdb_topic",
 *              autoCreateTopic = true,
 *              replicationFactor = 1,
 *              pollDuration = Duration.ofSeconds(1)
 *          ),
 *    ...
 * )
 * ```
 *
 * @property bootstrapServers A comma-separated list of host:port pairs to use for establishing the initial connection to the Kafka cluster.
 * @property topicName Name of the Kafka topic to use for the transaction log.
 * @property autoCreateTopic Whether to automatically create the topic, if it does not already exist.
 * @property replicationFactor The [replication factor](https://kafka.apache.org/documentation/#replication.factor) of the transaction log topic (if it is automatically created by XTDB).
 * @property pollDuration The maximum amount of time to block waiting for records to be returned by the Kafka consumer.
 * @property topicConfig A map of [topic configuration options](https://kafka.apache.org/documentation/#topicconfigs) to use when creating the transaction log topic (if it is automatically created by XTDB).
 * @property propertiesMap A map of Kafka connection properties, supplied directly to the Kafka client.
 * @property propertiesFile Path to a Java properties file containing Kafka connection properties, supplied directly to the Kafka client.
 */
@Serializable
@SerialName("!Kafka")
data class KafkaLogFactory @JvmOverloads constructor(
    @Serializable(with = StringWithEnvVarSerde::class) val bootstrapServers: String,
    @Serializable(with = StringWithEnvVarSerde::class) val topicName: String,
    var autoCreateTopic: Boolean = true,
    var replicationFactor: Int = 1,
    var pollDuration: Duration = Duration.ofSeconds(1),
    var topicConfig: Map<String, String> = emptyMap<String, String>(),
    var propertiesMap: Map<String, String> = emptyMap<String, String>(),
    @Serializable(with = PathWithEnvVarSerde::class) var propertiesFile: Path? = null
) : LogFactory {

    companion object {
        private val OPEN_LOG: IFn = requiringResolve("xtdb.kafka", "open-log")
    }

    fun autoCreateTopic(autoCreateTopic: Boolean) = apply { this.autoCreateTopic = autoCreateTopic }
    fun replicationFactor(replicationFactor: Int) = apply { this.replicationFactor = replicationFactor }
    fun pollDuration(pollDuration: Duration) = apply { this.pollDuration = pollDuration }
    fun topicConfig(topicConfig: Map<String, String>) = apply { this.topicConfig = topicConfig }
    fun propertiesMap(propertiesMap: Map<String, String>) = apply { this.propertiesMap = propertiesMap }
    fun propertiesFile(propertiesFile: Path) = apply { this.propertiesFile = propertiesFile }

    override fun openLog() = OPEN_LOG(this) as Log

    class Registration: ModuleRegistration {
        override fun register(registry: ModuleRegistry) {
            registry.registerLogFactory(KafkaLogFactory::class)
        }
    }
}
