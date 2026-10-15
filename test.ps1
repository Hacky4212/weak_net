$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force app/build/tests | Out-Null
javac -encoding UTF-8 -d app/build/tests app/src/main/java/cn/weaknet/lab/Profile.java app/src/main/java/cn/weaknet/lab/PacketSchedule.java app/src/main/java/cn/weaknet/lab/ResourceGroup.java app/src/main/java/cn/weaknet/lab/PacketBridge.java app/src/main/java/cn/weaknet/lab/LocalSocks.java tests/CoreTest.java tests/RobustnessTest.java
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
java -cp app/build/tests cn.weaknet.lab.CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
java -cp app/build/tests cn.weaknet.lab.RobustnessTest
if ($LASTEXITCODE -ne 0) { throw 'Robustness tests failed' }
