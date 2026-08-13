# CivicSignal backend

Run the complete website locally with:

```powershell
python server.py
```

Then open http://localhost:8000. The server stores reports, likes, comments, and uploaded images locally in `civicsignal.db` and `uploads/`.

To forward reports automatically, configure the SMTP variables in `.env.example` as system environment variables before starting the server. Without them, reports are safely stored with municipal status `queued`.
