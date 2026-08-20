# CivicSignal Java backend

The Python backend has been ported to Java. It serves the supplied CivicTrack frontend from `frontend/`, stores submitted reports in SQLite, and provides the reports API. The frontend now submits reports to the Java API rather than browser localStorage.

Run the complete website locally with:

```powershell
mvn compile exec:java
```

Then open http://localhost:8000. The server stores reports, likes, comments, and uploaded images locally in `civicsignal.db` and `uploads/`.

Set `PORT` to use a port other than `8000`. To forward reports automatically, configure `SMTP_HOST`, `MUNICIPAL_EMAIL`, and (if required) `SMTP_PORT`, `SMTP_TLS`, `SMTP_USER`, `SMTP_PASSWORD`, and `SMTP_FROM` as environment variables before starting the server. Without them, reports are safely stored with municipal status `queued`.
