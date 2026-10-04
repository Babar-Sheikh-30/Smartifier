# Smartifier — Byte Solutions

Project setup based on ByteMe's Maven folder layout, adapted for Byte Solutions with a separate Angular frontend. The landing page uses a Spring Boot and Thymeleaf shell with Angular content and an interactive knowledge-stack message preview.

## Layout

```text
Smartifier/
├── .github/workflows/          # Reserved for future CI workflows
├── .mvn/                      # Maven configuration and wrapper
├── .vscode/                   # Recommended editor extensions
├── docs/                      # Project documentation
├── frontend/                  # Angular workspace
│   ├── angular.json
│   ├── package.json
│   ├── tsconfig.json
│   ├── public/{images,fonts}/
│   └── src/
│       ├── app/
│       │   ├── core/{services,guards,interceptors,models}/
│       │   ├── shared/{components,directives,pipes}/
│       │   ├── features/
│       │   └── layouts/
│       ├── environments/
│       └── styles/
├── src/
│   ├── main/
│   │   ├── java/com/bytesolutions/smartifier/
│   │   │   ├── beans/
│   │   │   ├── bootstrap/
│   │   │   ├── config/
│   │   │   ├── controllers/
│   │   │   ├── models/
│   │   │   ├── repositories/
│   │   │   └── services/
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── static/{css,images,js}/
│   │       └── templates/fragments/
│   └── test/
│       ├── java/com/bytesolutions/smartifier/
│       └── resources/
├── mvnw / mvnw.cmd            # Portable Maven wrappers
├── mvn.cmd                    # Shortcut to installed workspace-local Maven
├── package.json               # npm workspace configuration
├── package-lock.json          # Shared frontend dependency lockfile
└── pom.xml                    # Spring Boot dependencies and project identity
```

Empty folders contain `.gitkeep` files so the structure survives cloning. The Java package base is `com.bytesolutions.smartifier`; the Angular component prefix is `bs`.

## Toolchain

- Node.js 22.19.0 and npm from the existing machine installation.
- Angular framework, CLI, and build tooling 20.3.33, updated from 20.2.2, with TypeScript 5.9.
- Java JDK 25 from the existing machine installation.
- Maven 3.9.11; wrapper scripts match ByteMe's layout and validate the distribution checksum.
- Spring Boot 4.1.1, updated from ByteMe's 3.5.6, with Spring MVC, Thymeleaf, and Jakarta Validation.
- JUnit Jupiter 6.0.3 and Mockito 5.23.0, managed by Spring Boot's test starter.

Angular 20 remains compatible with the installed Node version. See the [Angular compatibility table](https://angular.dev/reference/versions) and [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).

ByteMe's Stripe, JWT, MongoDB, JPA/H2, SOAP, Lombok, and embedded MongoDB dependencies are deferred until Smartifier requires those features. Its Docker configuration and application files were not copied. Angular assets belong in `frontend/public`; Thymeleaf assets belong in Spring's `src/main/resources/static`.

## Install and verify

Run in PowerShell from the repository root:

```powershell
npm.cmd ci
npm.cmd run ng -- version
npm.cmd run ng -- config projects.smartifier

# Keep Maven wrapper downloads inside this workspace.
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2'
.\mvnw.cmd --version
.\mvnw.cmd -B -ntp test
```

Maven libraries are cached in `.m2/repository` via `.mvn/maven.config`. The existing `mvn.cmd` shortcut also works with the Maven installation in `.tools`.

After installing dependencies with `npm.cmd ci`, start the complete application with `.\mvn.cmd spring-boot:run` and open http://localhost:8080. Maven builds Angular into `frontend/dist/` and copies its assets into `target/classes/static/app/`; generated Angular files stay outside the source tree. Run `.\mvn.cmd clean package` to build and package both parts in one command. For frontend live reload, use `npm.cmd start` and open http://localhost:4200.

For the Angular folder conventions, see [frontend/README.md](frontend/README.md).

