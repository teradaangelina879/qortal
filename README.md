# Qortal Data Node

## Important

This code is unfinished, and we haven't had the official genesis block for the data chain yet.
Therefore it is only possible to use this code if you first create your own test chain. I would
highly recommend waiting until the code is in a more complete state before trying to run this.

## Build / run

- Requires Java 11. OpenJDK 11 recommended over Java SE.
- Install Maven
- Use Maven to fetch dependencies and build: `mvn clean package`
- Built JAR should be something like `target/qortal-1.0.jar`
- Create basic *settings.json* file: `echo '{}' > settings.json`
- Run JAR in same working directory as *settings.json*: `java -jar target/qortal-1.0.jar`
- Wrap in shell script, add JVM flags, redirection, backgrounding, etc. as necessary.
- Or use supplied example shell script: *start.sh*
