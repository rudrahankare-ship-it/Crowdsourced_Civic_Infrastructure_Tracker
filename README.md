# CivicSignal

The repository is split into three areas:

- `backend/` contains the Java API, Maven build, and uploaded report images.
- `frontend/app/` contains the site served by the Java backend. `frontend/prototype/` preserves the older frontend prototype.
- `database/` contains database notes and an optional legacy SQLite file for import. Live reports, likes, and comments are stored in MongoDB Atlas.

The frontend submits reports, comments, and likes to the Java API rather than browser localStorage.

Create a MongoDB Atlas cluster and database user, and allow the machine running the backend to connect in the cluster's Network Access settings. Copy `.env.example` to `.env`, then replace `MONGODB_URI` with the connection string from Atlas. The server reads `.env` on startup; shell environment variables take precedence. Keep `.env` private and never commit it.

```powershell
mvn -f backend/pom.xml compile exec:java
```

Then open http://localhost:8000. On first startup, if the remote `reports` collection is empty and `database/civicsignal.db` exists, the server imports its reports, likes, and comments into Atlas. The SQLite file is left unchanged. If there is no local data to import, the server creates sample reports in Atlas. Uploaded images are stored under `backend/uploads/` and served by the API server.

The start page supports resident email/password sign-up and sign-in. Resident passwords are stored as salted PBKDF2 hashes in the MongoDB `users` collection; raw passwords are not stored. A sign-in session lasts up to 12 hours and is cleared when the backend restarts.

Corporation employees use the **Corporation employee sign in** link on the start page. This portal has no public sign-up. To provision the employee administrator, set `CIVICSIGNAL_ADMIN_EMAIL`, `CIVICSIGNAL_ADMIN_NAME`, and a unique `CIVICSIGNAL_ADMIN_PASSWORD` (8–128 characters) in the private `.env` file, then restart the backend. Startup creates or updates that account with the municipal role. The dashboard can view every report and update its status; those API routes require the municipal role on the server, not just a hidden UI.

Requirements: JDK 21+ and Maven. The API includes `GET`/`POST /api/reports`, `POST /api/reports/{id}/like`, and `POST /api/reports/{id}/comments`. Corporation endpoints are `GET /api/admin/reports` and `PATCH /api/admin/reports/{id}/status`; both require an employee session. `GET /api/reports` accepts an `X-Voter-Id` UUID and returns each report's `liked` state; the like endpoint requires this ID and toggles that browser's vote. The frontend creates and stores the ID per browser.

Set `PORT` to use a port other than `8000`. To forward reports automatically, configure `SMTP_HOST`, `MUNICIPAL_EMAIL`, and (if required) `SMTP_PORT`, `SMTP_TLS`, `SMTP_USER`, `SMTP_PASSWORD`, and `SMTP_FROM` in `.env` or as environment variables before starting the server. Without them, reports are stored with municipal status `queued`.

Deployment instructions for Render and the optional Vercel frontend are in [DEPLOYMENT.md](DEPLOYMENT.md).
