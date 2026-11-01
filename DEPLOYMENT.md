# Deployment

## Recommended: deploy the complete app on Render

The Java server serves both the website in `frontend/app/` and the API, so one Render web service can host the complete app.

1. In Render, choose **New → Blueprint** and connect this GitHub repository. Render reads `render.yaml` and uses the root `Dockerfile` to build the service.
2. Set the requested secret values when Render prompts for them: `MONGODB_URI`, `CIVICSIGNAL_ADMIN_EMAIL`, and `CIVICSIGNAL_ADMIN_PASSWORD`. Use an employee email and a unique password of 8 to 128 characters. `MONGODB_DATABASE` and `CIVICSIGNAL_ADMIN_NAME` already have defaults in the blueprint.
3. In MongoDB Atlas, add the Render service's outbound IP ranges to the cluster project's **Network Access** list. In Render, find them in the service's **Connect → Outbound** panel.
4. Wait for the Render deployment to become live, then open its `onrender.com` URL. The backend uses Render's `PORT` environment variable automatically.
5. Sign in to the employee portal with the configured admin email and password.

The Render free service uses an ephemeral filesystem. Report data remains in MongoDB, but locally uploaded photos under `backend/uploads/` can disappear after a restart or deploy. To preserve uploads, attach a persistent disk mounted at `/app/backend/uploads` (Render disks require a paid web service) or move uploads to object storage.

## Optional: use Vercel for the frontend

Deploy the backend on Render first and copy its service URL. Then create a Vercel project from this repository with:

- Root Directory: `frontend/app`
- Framework Preset: `Other`
- Build Command: blank
- Output Directory: `.`

Copy `frontend/app/vercel.json.example` to `frontend/app/vercel.json` and replace `REPLACE_WITH_RENDER_SERVICE` with the Render service hostname (without `https://` or a trailing slash). The rewrites proxy the app's `/api/...` calls and `/uploads/...` images to Render while keeping the browser on the Vercel domain. Commit and push `vercel.json`, then redeploy the Vercel project.

Do not put `MONGODB_URI` or `CIVICSIGNAL_ADMIN_PASSWORD` in Vercel: they belong in the Render service's environment variables. The frontend is static and does not need database credentials.
