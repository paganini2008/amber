# Online-mode fixtures

Tables with a few fake rows that the desktop samples reference, for trying online mode against real
servers. A person (or FixtureLoader) creates them once; Amber itself never runs SQL and only reads
catalog metadata. The files only create objects; they drop nothing.

    java -cp "amber-core/target/engine/lib/*" amber-test/src/test/java/com/github/amber/test/FixtureLoader.java <jdbc-url> <user> <password> <file>
