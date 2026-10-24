@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%"=="" @echo off
@if "%%i"=="1" set DEBUG=on

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set ImperialAppHome=%DIRNAME%

@rem Resolve JAVA_EXE
if defined JAVA_HOME (
    set JAVA_EXE="%JAVA_HOME%\bin\java.exe"
) else (
    set JAVA_EXE="java.exe"
)

set WRAPPER_JAR="%DIRNAME%gradle\wrapper\gradle-wrapper.jar"

%JAVA_EXE% -Xmx64m -Dfile.encoding=UTF-8 -jar %WRAPPER_JAR% %*
