# Smartifier by Byte Solutions

Smartifier delivers bite-sized knowledge by text message. The current application is a responsive landing page with membership information, example knowledge stacks, and an interactive text-message preview.

Members can register and log in, and members/admins have separate protected dashboards. Accounts and stack subscriptions are stored in local MongoDB. Members can subscribe to Everyday science, Words and language, and Learning habits; stack content is not displayed on the member page. Premium payments and SMS delivery are not implemented yet; the premium button currently displays an availability notice.

## Requirements

- Java JDK 25, available on your PATH. If set, `JAVA_HOME` must point to your JDK installation.
- Node.js matching `package.json`: Node 20.19+, 22.12+, or 24.x within those major versions. This workspace has been run with Node 22.19.0.
- npm, included with Node.js.
- MongoDB Community Server running locally (this workspace was verified against MongoDB 9.0.2). MongoDB Compass is optional for viewing accounts.
- Internet access for the first dependency installation.

The included Maven wrapper downloads Maven 3.9.11. You do not need to install Maven separately or install Angular globally.

## First-time setup

Open PowerShell in the repository root (the folder containing `pom.xml` and `package.json`):

```powershell
java -version
node --version
npm.cmd --version
npm.cmd ci

# Create the initial admin and start the application.
.\start-local.ps1 -SetupAdmin
```

Enter the admin email and password when prompted. Password input is hidden and is never saved to a file. The script temporarily supplies `SMARTIFIER_ADMIN_EMAIL` and `SMARTIFIER_ADMIN_PASSWORD` to the backend. The backend creates the admin only if that email is absent; it never resets an existing admin password or promotes a member. Remember your credentials; password recovery is not implemented yet.

The script uses JDK 25 from the Java installation on PATH, even if `JAVA_HOME` points to an older JDK. Supply `-JavaHome 'C:\path\to\jdk-25'` if needed. Maven dependencies are cached in `.m2/repository`; the wrapper distribution uses its normal user cache unless `MAVEN_USER_HOME` is set.

## Run the complete application

From the repository root:

```powershell
.\start-local.ps1
```

Open http://localhost:8080. Stop the application with Ctrl+C in its terminal.

Spring Boot serves a Thymeleaf HTML shell, and Angular renders the pages. Maven builds Angular into `frontend/dist/` and copies the files to `target/classes/static/app/` before starting the server.

After changing frontend files, stop the server and run the command again, then refresh your browser. Use Ctrl+F5 if it still displays an older version. Running `npm.cmd run build` alone only updates `frontend/dist/`; it does not copy the new files into Spring Boot's asset folder.

## Frontend development with live reload

```powershell
npm.cmd start
```

Keep Spring Boot running on port 8080, then open http://localhost:4200. Angular reloads the page when you save frontend changes and proxies `/api/**` to Spring Boot. Use one browser origin consistently during a session.

## Local accounts

The default MongoDB connection is `mongodb://localhost:27017/smartifier`. Accounts live in the `users` collection. Override the connection with `MONGODB_URI` if needed. Compass can connect to `mongodb://localhost:27017` to inspect the data.

Open `/register` to create a member with name, email, mobile number including country code (such as `+14165551234`), password, and confirmation. Registration redirects to `/login`. Members and admins share the same login page and are directed to `/member/dashboard` and `/admin/dashboard`, respectively. Each dashboard displays the account information and a logout button. Members can update their mobile number and subscribe or unsubscribe from the three free stacks. Existing accounts without a mobile number must add one before subscribing.

The backend stores BCrypt password hashes and creates a unique index on normalized emails. Registration always assigns `MEMBER`, even if a client supplies an admin role. Resolve any pre-existing duplicate emails before startup so the index can be created.

Passwords must contain 12–64 characters and at most 72 UTF-8 bytes. Password confirmation is validated and never stored. Authentication uses HTTP-only session cookies, CSRF tokens, and backend role enforcement. Sessions expire after 30 minutes of inactivity and are lost when the backend restarts; accounts persist on disk. Set `SESSION_COOKIE_SECURE=true` when deploying behind HTTPS.

## Build and check

```powershell
# Frontend build and formatting check
npm.cmd run build
npm.cmd run format:check

# Maven commands require JAVA_HOME to point to JDK 25 (adjust this path).
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1'
# Unit tests do not need MongoDB.
.\mvnw.cmd -B -ntp test
# Include HTTP account tests against local MongoDB.
.\mvnw.cmd -B -ntp '-Dsmartifier.mongo.tests=true' test
# Build both parts, run unit tests, and create the executable JAR.
.\mvnw.cmd clean package

# Run the packaged application
java -jar target/smartifier-0.0.1-SNAPSHOT.jar
```

Use `npm.cmd run format` to apply Prettier formatting. Generated files in `frontend/dist/` and `target/` are ignored by Git.

MongoDB integration tests use a uniquely named `smartifier_auth_test_*` database and remove it afterward. They do not use the application's `smartifier` database.

## Project layout

```text
Smartifier/
  frontend/
    src/app/app.ts         Router shell
    src/app/landing.*      Landing page and knowledge-stack preview
    src/app/auth-page.*    Registration and login forms
    src/app/auth.service.ts Session and authentication requests
    src/app/routes.ts      Routes and dashboard guards
    src/app/dashboard.ts   Member/admin dashboard placeholders
    src/styles.scss       Styling and responsive layouts
    src/main.ts           Angular entry point
    src/index.html        Frontend development HTML shell
    angular.json          Angular build and development server settings
  src/main/
    java/com/bytesolutions/smartifier/
      SmartifierApplication.java          Spring Boot entry point
      controllers/          HTML shell, account API, and validation errors
      config/               Spring Security configuration
      models/               MongoDB account document
      repositories/         Account storage
      services/             Registration and authentication
      bootstrap/            Unique email index and initial admin setup
    resources/
      templates/landing.html  Thymeleaf HTML shell
      application.properties  Spring configuration
  .mvn/                   Maven wrapper configuration
  mvnw / mvnw.cmd         Maven wrapper scripts
  start-local.ps1         Local startup and optional initial admin prompts
  pom.xml                 Java dependencies and combined build
  package.json            Frontend and formatting commands
  package-lock.json       Locked npm dependency versions
```

Additional empty folders are placeholders for future features. Angular uses version 20.3.33 and Spring Boot uses version 4.1.1, as configured in the package files and `pom.xml`.

## Troubleshooting

- **PowerShell blocks `npm`:** use `npm.cmd`, as shown above.
- **Java compilation reports an unsupported release:** check `java -version` and `.\mvnw.cmd --version`; Maven must use JDK 25.
- **PowerShell blocks `start-local.ps1`:** use `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-local.ps1 -SetupAdmin` for the first launch. Omit `-SetupAdmin` on later launches. The policy override applies to that process only.
- **Port 8080 is already in use:** stop the other server or run `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=8081'` and open http://localhost:8081.
- **`mvn.cmd` reports Maven is missing:** use `.\mvnw.cmd` instead. The `mvn.cmd` shortcut depends on an ignored local installation in `.tools/`.
- **MongoDB connection refused or startup hangs:** ensure the MongoDB Windows service is running and Compass can connect to the configured URI.

See [frontend/README.md](frontend/README.md) for frontend-specific instructions.
