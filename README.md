# Smartifier by Byte Solutions

Smartifier delivers bite-sized knowledge by text message. The current application is a responsive landing page with membership information, example knowledge stacks, and an interactive text-message preview.

The header links to `/login` and `/register`. Those pages are not implemented yet. Memberships, subscriptions, and SMS delivery are also not implemented.

## Requirements

- Java JDK 25, available on your PATH. If set, `JAVA_HOME` must point to your JDK installation.
- Node.js matching `package.json`: Node 20.19+, 22.12+, or 24.x within those major versions. This workspace has been run with Node 22.19.0.
- npm, included with Node.js.
- Internet access for the first dependency installation.

The included Maven wrapper downloads Maven 3.9.11. You do not need to install Maven separately or install Angular globally.

## First-time setup

Open PowerShell in the repository root (the folder containing `pom.xml` and `package.json`):

```powershell
java -version
node --version
npm.cmd --version
npm.cmd ci

# Set this in each new PowerShell session before using the Maven wrapper.
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2'
.\mvnw.cmd --version
```

Maven downloads and dependency caches stay in `.m2/`. Setting this variable also avoids a wrapper error encountered with the default user Maven folder on this machine.

## Run the complete application

From the repository root:

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2'
.\mvnw.cmd spring-boot:run
```

Open http://localhost:8080. Stop the application with Ctrl+C in its terminal.

Spring Boot serves a Thymeleaf HTML shell, and Angular renders the landing page. Maven builds Angular into `frontend/dist/` and copies the files to `target/classes/static/app/` before starting the server.

After changing frontend files, stop the server and run the command again, then refresh your browser. Use Ctrl+F5 if it still displays an older version. Running `npm.cmd run build` alone only updates `frontend/dist/`; it does not copy the new files into Spring Boot's asset folder.

## Frontend development with live reload

```powershell
npm.cmd start
```

Open http://localhost:4200. Angular reloads the page when you save frontend changes. This runs the frontend using Angular's HTML shell; it does not start Spring Boot. Use port 8080 to check the complete application.

## Build and check

```powershell
# Frontend build and formatting check
npm.cmd run build
npm.cmd run format:check

# Build both parts, run any Java tests, and create the executable JAR
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2'
.\mvnw.cmd clean package

# Run the packaged application
java -jar target/smartifier-0.0.1-SNAPSHOT.jar
```

Use `npm.cmd run format` to apply Prettier formatting. Generated files in `frontend/dist/` and `target/` are ignored by Git.

## Project layout

```text
Smartifier/
  frontend/
    src/app/app.html       Landing page markup
    src/app/app.ts         Knowledge-stack preview state
    src/styles.scss       Styling and responsive layouts
    src/main.ts           Angular entry point
    src/index.html        Frontend development HTML shell
    angular.json          Angular build and development server settings
  src/main/
    java/com/bytesolutions/smartifier/
      SmartifierApplication.java          Spring Boot entry point
      controllers/LandingController.java  Serves the landing page at /
    resources/
      templates/landing.html  Thymeleaf HTML shell
      application.properties  Spring configuration
  .mvn/                   Maven wrapper configuration
  mvnw / mvnw.cmd         Maven wrapper scripts
  pom.xml                 Java dependencies and combined build
  package.json            Frontend and formatting commands
  package-lock.json       Locked npm dependency versions
```

Additional empty folders are placeholders for future features. Angular uses version 20.3.33 and Spring Boot uses version 4.1.1, as configured in the package files and `pom.xml`.

## Troubleshooting

- **PowerShell blocks `npm`:** use `npm.cmd`, as shown above.
- **Java compilation reports an unsupported release:** check `java -version` and `.\mvnw.cmd --version`; Maven must use JDK 25.
- **The wrapper reports `Cannot index into a null array`:** set `MAVEN_USER_HOME` using the setup command above, then retry.
- **Port 8080 is already in use:** stop the other server or run `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=8081'` and open http://localhost:8081.
- **`mvn.cmd` reports Maven is missing:** use `.\mvnw.cmd` instead. The `mvn.cmd` shortcut depends on an ignored local installation in `.tools/`.
- **Login or registration returns an error page:** only the navigation links exist so far; their destination pages still need to be built.

See [frontend/README.md](frontend/README.md) for frontend-specific instructions.
