# Smartifier frontend

Angular renders the responsive landing page and interactive knowledge-stack message preview. Spring MVC serves the Thymeleaf shell at `/` with context-aware asset URLs and a server-provided page title.

From the repository root:

```powershell
npm.cmd ci
.\mvn.cmd spring-boot:run
```

Open http://localhost:8080 for the combined Thymeleaf and Angular page. Maven builds Angular into `frontend/dist/`, then copies the generated files into `target/classes/static/app/`. Both output folders are ignored by Git. Restart through Maven after frontend changes to rebuild and copy the assets. CSS remains unminified for readability.

Run `.\mvn.cmd clean package` to build the frontend and package the complete application in one command (after `npm.cmd ci` on initial setup). For a frontend-only build, use `npm.cmd run build`.

For frontend development with live reload, run `npm.cmd start` and open http://localhost:4200. This uses Angular's development HTML shell. The message preview uses illustrative stacks and local UI state. It does not create memberships, save subscriptions, or send SMS messages.
