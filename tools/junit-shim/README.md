# JUnit shim (offline builds only)

The tests in `src/test/java` are ordinary JUnit 5 tests and a normal build runs them on the
real JUnit Platform (`./gradlew test`).

This directory exists because StockPulse was first developed in a sandbox with no access to
Maven Central. It holds a tiny stand-in for the part of the JUnit 5 API the tests use
(`@Test`, `@BeforeEach`, `@AfterEach`, `@BeforeAll`, `@AfterAll`, `Assertions`) and a runner
with the same default lifecycle (a new test-class instance per test method). It is only used
when you pass `-PofflineLibs=<dir>` with the PostgreSQL JDBC jar in `<dir>`:

    gradle shimTest -PofflineLibs=/path/to/jars

Do not use it otherwise.
