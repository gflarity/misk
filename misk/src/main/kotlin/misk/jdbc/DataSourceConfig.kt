package misk.jdbc

import misk.config.Config
import java.time.Duration

/** Defines a type of datasource */
enum class DataSourceType(
  val driverClassName: String,
  val hibernateDialect: String,
  val buildJdbcUrl: (DataSourceConfig) -> String
) {
  MYSQL(
      driverClassName = "com.mysql.jdbc.Driver",
      hibernateDialect = "org.hibernate.dialect.MySQL57Dialect",
      buildJdbcUrl = { config ->
        val port = config.port ?: 3306
        val host = config.host ?: "127.0.0.1"
        val database = config.database ?: ""
        "jdbc:mysql://$host:$port/$database"
      }
  ),
  HSQLDB(
      driverClassName = "org.hsqldb.jdbcDriver",
      hibernateDialect = "org.hibernate.dialect.H2Dialect",
      buildJdbcUrl = { config ->
        "jdbc:hsqldb:mem:${config.database!!};sql.syntax_mys=true"
      }
  )
}

/** Configuration element for an individual datasource */
data class DataSourceConfig(
  val type: DataSourceType,
  val host: String? = null,
  val port: Int? = null,
  val database: String? = null,
  val username: String? = null,
  val password: String? = null,
  val connection_properties: Map<String, String> = mapOf(),
  val fixed_pool_size: Int = 10,
  val connection_timeout: Duration = Duration.ofSeconds(30),
  val connection_max_lifetime: Duration = Duration.ofMinutes(30),
  val migrations_path: String
)

/** Configuration element for a cluster of DataSources */
data class DataSourceClusterConfig(
  val writer: DataSourceConfig,
  val reader: DataSourceConfig?
)

/** Top-level configuration element for all datasource clusters */
class DataSourceClustersConfig : LinkedHashMap<String, DataSourceClusterConfig>, Config {
  constructor() : super()
  constructor(m: Map<String, DataSourceClusterConfig>) : super(m)
}
