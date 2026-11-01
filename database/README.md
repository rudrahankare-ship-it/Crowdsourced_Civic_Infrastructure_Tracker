# Database

The live database is hosted in MongoDB Atlas and is selected with `MONGODB_DATABASE` in the project root `.env` file. The backend creates and uses these collections:

- `reports`: civic reports, with embedded likes and comments
- `counters`: report ID sequence
- `setup`: one-time initialization and SQLite import status

For a legacy SQLite import, place `civicsignal.db` in this folder before the first server startup. New application data is written to MongoDB Atlas; this folder is not a local MongoDB server.
