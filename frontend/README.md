# Smartifier frontend — Byte Solutions

Angular workspace and folder preparation only. The workspace registers the `smartifier` application with component prefix `bs` and SCSS defaults, but no application source, entry point, routes, components, or build/serve/test targets have been created yet.

Install dependencies from the repository root with `npm.cmd ci`. npm workspaces share the root lockfile and install dependencies in the root `node_modules` directory.

From the root, use `npm.cmd run ng -- version` to inspect the local CLI, or `npm.cmd run ng -- config projects.smartifier` to inspect the application configuration.

## Source folders

- `src/app/core`: application-wide services, guards, interceptors, and models.
- `src/app/shared`: reusable components, directives, and pipes.
- `src/app/features`: future feature pages and components.
- `src/app/layouts`: future page layouts.
- `src/environments`: future environment configuration; do not put secrets in browser configuration.
- `src/styles`: shared SCSS styles.
- `public/images`, `public/fonts`: future static assets.

The empty folders use `.gitkeep` placeholders so Git preserves the layout. Application implementation and runnable Angular targets are deferred.
