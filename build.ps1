
$env:JAVA_HOME = "D:\Program Files\Java\jdk-24.0.2"
$env:Path = "$env:JAVA_HOME\bin;$($env:Path)"
Write-Host "Switched to Java 24"
mvn -DskipTests clean package