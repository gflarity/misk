package misk.hibernate

import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.Service
import misk.MiskModule
import misk.config.Config
import misk.config.MiskConfig
import misk.environment.Environment
import misk.inject.KAbstractModule
import misk.inject.addMultibinderBinding
import misk.jdbc.DataSourceClustersConfig
import misk.jdbc.DataSourceConfig
import misk.jdbc.InMemoryHsqlService
import misk.resources.FakeResourceLoader
import misk.resources.FakeResourceLoaderModule
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.Assert.fail
import org.junit.jupiter.api.Test
import javax.inject.Inject
import javax.inject.Provider
import javax.persistence.PersistenceException

@MiskTest(startService = true)
internal class SchemaMigratorTest {
  val defaultEnv = Environment.TESTING
  val rootConfig = MiskConfig.load<RootConfig>("test_hibernate_app", defaultEnv)
  val config: DataSourceConfig = rootConfig.data_source_clusters["exemplar"]!!.writer

  @MiskTestModule
  val module = object : KAbstractModule() {
    override fun configure() {
      install(MiskModule())
      install(FakeResourceLoaderModule())

      binder().addMultibinderBinding<Service>().toInstance(
          InMemoryHsqlService(
              config = config,
              setUpStatements = listOf(
                  "DROP TABLE IF EXISTS schema_version",
                  "DROP TABLE IF EXISTS table_1",
                  "DROP TABLE IF EXISTS table_2",
                  "DROP TABLE IF EXISTS table_3",
                  "DROP TABLE IF EXISTS table_4"
              )
          )
      )

      val hibernateConnector = HibernateConnector(Movies::class, config, setOf())
      binder().addMultibinderBinding<Service>().toInstance(object : AbstractIdleService() {
        override fun startUp() = hibernateConnector.connect()
        override fun shutDown() = hibernateConnector.disconnect()
      })

      bind(SessionFactory::class.java).toProvider(hibernateConnector)
    }
  }

  @Inject lateinit var resourceLoader: FakeResourceLoader
  @Inject lateinit var sessionFactoryProvider: Provider<SessionFactory>

  @Test fun initializeAndMigrate() {
    val schemaMigrator = SchemaMigrator(resourceLoader, sessionFactoryProvider.get(), config)

    resourceLoader.put("${config.migrations_path}/v1001__movies.sql", """
        |CREATE TABLE table_1 (name varchar(255))
        |""".trimMargin())
    resourceLoader.put("${config.migrations_path}/v1002__movies.sql", """
        |CREATE TABLE table_2 (name varchar(255))
        |""".trimMargin())

    // Initially the schema_version table is absent.
    assertThat(tableExists("schema_version")).isFalse()
    assertThat(tableExists("table_1")).isFalse()
    assertThat(tableExists("table_2")).isFalse()
    assertThat(tableExists("table_3")).isFalse()
    assertThat(tableExists("table_4")).isFalse()
    try {
      schemaMigrator.appliedMigrations()
      fail()
    } catch (expected: PersistenceException) {
    }

    // Once we initialize, that table is present but empty.
    schemaMigrator.initialize()
    assertThat(schemaMigrator.appliedMigrations()).isEmpty()
    assertThat(tableExists("schema_version")).isTrue()
    assertThat(tableExists("table_1")).isFalse()
    assertThat(tableExists("table_2")).isFalse()
    assertThat(tableExists("table_3")).isFalse()
    assertThat(tableExists("table_4")).isFalse()

    // When we apply migrations, the table is present and contains the applied migrations.
    schemaMigrator.applyAll("SchemaMigratorTest", setOf())
    assertThat(schemaMigrator.appliedMigrations()).containsExactly(1001, 1002)
    assertThat(tableExists("schema_version")).isTrue()
    assertThat(tableExists("table_1")).isTrue()
    assertThat(tableExists("table_2")).isTrue()
    assertThat(tableExists("table_3")).isFalse()
    assertThat(tableExists("table_4")).isFalse()
    schemaMigrator.requireAll()

    // When new migrations are added they can be applied.
    resourceLoader.put("${config.migrations_path}/v1003__movies.sql", """
        |CREATE TABLE table_3 (name varchar(255))
        |""".trimMargin())
    resourceLoader.put("${config.migrations_path}/v1004__movies.sql", """
        |CREATE TABLE table_4 (name varchar(255))
        |""".trimMargin())
    schemaMigrator.applyAll("SchemaMigratorTest", setOf(1001, 1002))
    assertThat(schemaMigrator.appliedMigrations()).containsExactly(1001, 1002, 1003, 1004)
    assertThat(tableExists("schema_version")).isTrue()
    assertThat(tableExists("table_1")).isTrue()
    assertThat(tableExists("table_2")).isTrue()
    assertThat(tableExists("table_3")).isTrue()
    assertThat(tableExists("table_4")).isTrue()
    schemaMigrator.requireAll()
  }

  @Test fun requireAllWithMissingMigrations() {
    val schemaMigrator = SchemaMigrator(resourceLoader, sessionFactoryProvider.get(), config)
    schemaMigrator.initialize()

    resourceLoader.put("${config.migrations_path}/v1001__foo.sql", """
        |CREATE TABLE table_1 (name varchar(255))
        |""".trimMargin())
    resourceLoader.put("${config.migrations_path}/v1002__foo.sql", """
        |CREATE TABLE table_1 (name varchar(255))
        |""".trimMargin())

    try {
      schemaMigrator.requireAll()
      fail()
    } catch (expected: IllegalStateException) {
      assertThat(expected).hasMessage("""
          |lorfil is missing migrations:
          |  ${config.migrations_path}/v1001__foo.sql
          |  ${config.migrations_path}/v1002__foo.sql""".trimMargin())
    }
  }

  @Test fun resourceVersionParsing() {
    val sm = SchemaMigrator(resourceLoader, sessionFactoryProvider.get(), config)

    assertThat(sm.resourceVersionOrNull("foo/migrations/v100__bar.sql")).isEqualTo(100)
    assertThat(sm.resourceVersionOrNull("foo/migrations/v100__v200.sql")).isEqualTo(100)
    assertThat(sm.resourceVersionOrNull("v100_foo/migrations/v200__bar.sql")).isEqualTo(200)
    assertThat(sm.resourceVersionOrNull("v100_foo/migrations")).isNull()
    assertThat(sm.resourceVersionOrNull("v100_foo/migrations/")).isNull()
    assertThat(sm.resourceVersionOrNull("v100__bar.sql")).isEqualTo(100)
    assertThat(sm.resourceVersionOrNull("foo/migrations/v100__bar.SQL")).isNull()
    assertThat(sm.resourceVersionOrNull("foo/migrations/V100__bar.sql")).isNull()
    assertThat(sm.resourceVersionOrNull("foo/migrations/v100_.sql")).isNull()
    assertThat(sm.resourceVersionOrNull("foo/migrations/v100__.sql")).isNull()
    assertThat(sm.resourceVersionOrNull("foo/migrations/v100__.sql")).isNull()
  }

  fun tableExists(table: String): Boolean {
    try {
      sessionFactoryProvider.get().openSession().use { session ->
        session.createNativeQuery("SELECT * FROM $table LIMIT 1").list()
      }
      return true
    } catch (e: PersistenceException) {
      return false
    }
  }

  private fun SchemaMigrator.resourceVersionOrNull(resource: String): Int? {
    try {
      return resourceVersion(resource)
    } catch (expected: IllegalArgumentException) {
      return null
    }
  }

  data class RootConfig(val data_source_clusters: DataSourceClustersConfig) : Config
}