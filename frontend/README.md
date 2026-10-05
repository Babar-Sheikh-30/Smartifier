# Smartifier frontend

Angular renders the responsive landing page and interactive knowledge-stack message preview. Spring MVC serves the Thymeleaf shell at `/` with context-aware asset URLs and a server-provided page title.

From the repository root:

```powershell
npm.cmd ci
.\start-local.ps1 -SetupAdmin
```

Open http://localhost:8080 for the combined Thymeleaf and Angular page. Maven builds Angular into `frontend/dist/`, then copies the generated files into `target/classes/static/app/`. Both output folders are ignored by Git. Restart through Maven after frontend changes to rebuild and copy the assets. CSS remains unminified for readability.

Use `.\start-local.ps1` on subsequent launches. Local MongoDB must be running. The first launch prompts for an admin email/password; later launches use the stored account. See the root README for account setup and testing. Run `.\mvnw.cmd clean package` with `JAVA_HOME` set to JDK 25 to package both parts. For a frontend-only build, use `npm.cmd run build`; restart through Maven to copy the changes into Spring Boot's asset folder.

For frontend development with live reload, keep Spring Boot running on port 8080, run `npm.cmd start` in another terminal, and open http://localhost:4200. Angular proxies `/api/**` to the backend. Registration creates members in MongoDB, and login directs members/admins to protected dashboard placeholders. The message preview uses illustrative stacks and local UI state. Subscriptions and SMS delivery are not implemented yet.
