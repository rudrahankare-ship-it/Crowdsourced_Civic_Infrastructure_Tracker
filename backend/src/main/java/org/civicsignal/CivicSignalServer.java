package org.civicsignal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.bson.Document;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Updates.*;

/** Standalone CivicSignal backend. It serves the static site and the reports API. */
public final class CivicSignalServer {
  private static final Path ROOT = Path.of("").toAbsolutePath();
  // Keep the web app, backend runtime files, and database documentation/data separate.
  private static final Path STATIC_ROOT = ROOT.resolve("frontend/app").normalize();
  private static final Path UPLOADS = ROOT.resolve("backend/uploads");
  private static MongoClient mongoClient;
  private static MongoCollection<Document> reports;
  private static MongoCollection<Document> counters;
  private static MongoCollection<Document> setup;
  private static MongoCollection<Document> users;
  private static final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int SESSION_SECONDS = 12 * 60 * 60;
  private static final int PASSWORD_ITERATIONS = 210_000;
  private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
  private static final Map<String, String> IMAGE_EXTENSIONS = Map.of("image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp");
  private static final Pattern ACTION = Pattern.compile("^/api/reports/(\\d+)/(like|comments)$");
  private static final Pattern ADMIN_STATUS = Pattern.compile("^/api/admin/reports/(\\d+)/status$");

  public static void main(String[] args) throws Exception {
    loadDotEnv();
    initializeDatabase();
    int port = Integer.parseInt(env("PORT", "8000"));
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", CivicSignalServer::handle);
    server.setExecutor(Executors.newCachedThreadPool());
    System.out.println("CivicSignal running at http://localhost:" + port);
    server.start();
  }

  /** Load simple KEY=value settings from .env without replacing shell variables. */
  private static void loadDotEnv() throws IOException {
    Path file = ROOT.resolve(".env");
    if (!Files.isRegularFile(file)) return;
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String entry = line.trim();
      if (entry.isEmpty() || entry.startsWith("#")) continue;
      int separator = entry.indexOf('=');
      if (separator < 1) continue;
      String key = entry.substring(0, separator).trim();
      String value = entry.substring(separator + 1).trim();
      if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
      if (!key.isEmpty() && System.getenv(key) == null) System.setProperty("civicsignal.env." + key, value);
    }
  }

  private static void initializeDatabase() throws Exception {
    Files.createDirectories(UPLOADS);
    String uri = env("MONGODB_URI", "");
    if (uri == null || uri.isBlank()) throw new IllegalStateException("Set MONGODB_URI to your MongoDB Atlas connection string.");
    mongoClient = MongoClients.create(uri);
    MongoDatabase database = mongoClient.getDatabase(env("MONGODB_DATABASE", "civicsignal"));
    database.runCommand(new Document("ping", 1));
    reports = database.getCollection("reports");
    counters = database.getCollection("counters");
    setup = database.getCollection("setup");
    users = database.getCollection("users");
    users.createIndex(Indexes.ascending("email"), new IndexOptions().unique(true));
    ensureMunicipalAccount();

    Path legacyDatabase = ROOT.resolve("database/civicsignal.db");
    if (setup.find(eq("_id", "initialized")).first() == null) {
      Document migration = setup.find(eq("_id", "sqlite-import")).first();
      boolean resumingMigration = migration != null && "in_progress".equals(migration.getString("status"));
      if (resumingMigration || (migration == null && reports.countDocuments() == 0 && Files.isRegularFile(legacyDatabase))) {
        if (!Files.isRegularFile(legacyDatabase)) throw new IllegalStateException("The SQLite import was interrupted, but civicsignal.db is no longer available.");
        if (!resumingMigration) setup.insertOne(new Document("_id", "sqlite-import").append("status", "in_progress"));
        migrateSqlite(legacyDatabase);
        setup.updateOne(eq("_id", "sqlite-import"), set("status", "complete"));
      }
      if (reports.countDocuments() == 0) seed();
      setup.insertOne(new Document("_id", "initialized"));
    }
    long highestId = reports.find().sort(descending("_id")).first().getLong("_id");
    counters.updateOne(eq("_id", "reports"), max("value", highestId), new UpdateOptions().upsert(true));
  }

  private static long nextReportId() {
    Document counter = counters.findOneAndUpdate(eq("_id", "reports"), inc("value", 1L),
        new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
    return counter.getLong("value");
  }

  private static void ensureMunicipalAccount() throws GeneralSecurityException {
    String email = env("CIVICSIGNAL_ADMIN_EMAIL", "").trim().toLowerCase(Locale.ROOT);
    String password = env("CIVICSIGNAL_ADMIN_PASSWORD", "");
    if (email.isBlank() && password.isBlank()) {
      System.out.println("Corporation employee sign-in is disabled. Configure CIVICSIGNAL_ADMIN_EMAIL and CIVICSIGNAL_ADMIN_PASSWORD in .env to enable it.");
      return;
    }
    if (email.isBlank() || password.isBlank()) throw new IllegalStateException("Set both CIVICSIGNAL_ADMIN_EMAIL and CIVICSIGNAL_ADMIN_PASSWORD to enable corporation employee sign-in.");
    if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || password.length() < 8 || password.length() > 128) {
      throw new IllegalStateException("The corporation admin email must be valid and its password must be 8 to 128 characters.");
    }
    String name = env("CIVICSIGNAL_ADMIN_NAME", "Corporation staff").trim();
    if (name.isBlank() || name.length() > 80) name = "Corporation staff";
    String salt = newSalt();
    String hash = passwordHash(password, salt);
    users.updateOne(eq("email", email), combine(set("name", name), set("role", "municipal"),
        set("password_salt", salt), set("password_hash", hash),
        setOnInsert("_id", UUID.randomUUID().toString()), setOnInsert("created_at", Instant.now().toString())),
        new UpdateOptions().upsert(true));
    System.out.println("Corporation employee sign-in enabled for " + email);
  }

  private static void seed() {
    String now = Instant.now().toString();
    String[][] rows = {{"Road damage", "Gandhi Nagar, Ward 12", "Large pothole causing traffic to swerve near the school gate", "assets/pothole-report.png", "sent"}, {"Streetlight", "Aundh, Ward 8", "Three streetlights out on the walking path by the park", "assets/streetlight-report.png", "under review"}, {"Footpath", "Kothrud, Ward 4", "Broken pavement tiles make this bus stop inaccessible", "assets/footpath-report.png", "needs attention"}};
    List<Document> seedReports = new ArrayList<>();
    for (int i = 0; i < rows.length; i++) {
      String[] row = rows[i];
      seedReports.add(new Document("_id", (long) i + 1)
          .append("issue_type", row[0]).append("location", row[1]).append("description", row[2])
          .append("image_url", row[3]).append("created_at", now).append("municipal_status", row[4])
          .append("liked_by", List.of()).append("likes_count", 0).append("comments", List.of()));
    }
    for (Document report : seedReports) {
      reports.replaceOne(eq("_id", report.getLong("_id")), report, new ReplaceOptions().upsert(true));
    }
  }

  private static void migrateSqlite(Path sqliteFile) throws SQLException {
    Map<Long, Document> imported = new LinkedHashMap<>();
    Map<Long, Set<String>> likedBy = new HashMap<>();
    Map<Long, Integer> legacyLikes = new HashMap<>();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("SELECT id,issue_type,location,description,image_url,created_at,municipal_status FROM reports")) {
      while (rows.next()) {
        long id = rows.getLong("id");
        imported.put(id, new Document("_id", id)
            .append("issue_type", rows.getString("issue_type")).append("location", rows.getString("location"))
            .append("description", rows.getString("description")).append("image_url", rows.getString("image_url"))
            .append("created_at", rows.getString("created_at")).append("municipal_status", rows.getString("municipal_status"))
            .append("comments", new ArrayList<Document>()));
        likedBy.put(id, new LinkedHashSet<>());
      }
    }
    if (imported.isEmpty()) return;

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("SELECT report_id,voter_id FROM likes ORDER BY id")) {
      while (rows.next()) {
        long reportId = rows.getLong("report_id");
        if (!imported.containsKey(reportId)) continue;
        String voterId = rows.getString("voter_id");
        if (voterId == null) legacyLikes.merge(reportId, 1, Integer::sum);
        else likedBy.get(reportId).add(voterId);
      }
    }
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("SELECT id,report_id,author,body,created_at FROM comments ORDER BY id")) {
      while (rows.next()) {
        Document report = imported.get(rows.getLong("report_id"));
        if (report == null) continue;
        report.getList("comments", Document.class).add(new Document("author", rows.getString("author"))
            .append("body", rows.getString("body")).append("created_at", rows.getString("created_at")));
      }
    }
    for (Map.Entry<Long, Document> entry : imported.entrySet()) {
      long id = entry.getKey();
      entry.getValue().append("liked_by", new ArrayList<>(likedBy.get(id)))
          .append("likes_count", likedBy.get(id).size() + legacyLikes.getOrDefault(id, 0));
    }
    for (Document report : imported.values()) {
      reports.replaceOne(eq("_id", report.getLong("_id")), report, new ReplaceOptions().upsert(true));
    }
  }

  private static void handle(HttpExchange e) throws IOException {
    try {
      String path = e.getRequestURI().getPath();
      if ("GET".equals(e.getRequestMethod()) && "/api/auth/session".equals(path)) { authSession(e); return; }
      if ("POST".equals(e.getRequestMethod()) && "/api/auth/register".equals(path)) { register(e); return; }
      if ("POST".equals(e.getRequestMethod()) && "/api/auth/login".equals(path)) { login(e); return; }
      if ("POST".equals(e.getRequestMethod()) && "/api/auth/logout".equals(path)) { logout(e); return; }
      if ("GET".equals(e.getRequestMethod()) && "/api/admin/reports".equals(path)) { if (requireMunicipalSession(e) == null) return; listAdminReports(e); return; }
      Matcher adminStatus = ADMIN_STATUS.matcher(path);
      if ("PATCH".equals(e.getRequestMethod()) && adminStatus.matches()) { SessionInfo staff = requireMunicipalSession(e); if (staff == null) return; updateReportStatus(e, Long.parseLong(adminStatus.group(1)), staff); return; }
      if ("GET".equals(e.getRequestMethod()) && "/api/reports".equals(path)) { if (requireSession(e) == null) return; listReports(e); return; }
      if ("POST".equals(e.getRequestMethod()) && "/api/reports".equals(path)) { if (requireSession(e) == null) return; createReport(e); return; }
      Matcher match = ACTION.matcher(path);
      if ("POST".equals(e.getRequestMethod()) && match.matches()) { if (requireSession(e) == null) return; if ("like".equals(match.group(2))) like(e, Long.parseLong(match.group(1))); else comment(e, Long.parseLong(match.group(1))); return; }
      if (path.startsWith("/api/")) { json(e, 404, "{\"error\":\"Not found\"}"); return; }
      serveStatic(e, path);
    } catch (IllegalArgumentException ex) { json(e, 400, error(ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); json(e, 500, error("Internal server error")); }
  }

  private static void register(HttpExchange e) throws IOException, GeneralSecurityException {
    Document body = requestDocument(e);
    String name = stringField(body, "name").trim();
    String email = stringField(body, "email").trim().toLowerCase(Locale.ROOT);
    String password = stringField(body, "password");
    if (name.isBlank() || name.length() > 80) throw new IllegalArgumentException("Enter a name up to 80 characters.");
    if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid email address.");
    if (password.length() < 8 || password.length() > 128) throw new IllegalArgumentException("Password must be between 8 and 128 characters.");
    String salt = newSalt();
    Document user = new Document("_id", UUID.randomUUID().toString()).append("name", name)
        .append("email", email).append("role", "resident").append("password_salt", salt).append("password_hash", passwordHash(password, salt))
        .append("created_at", Instant.now().toString());
    try { users.insertOne(user); }
    catch (com.mongodb.MongoWriteException ex) { if (ex.getCode() == 11000) { json(e, 409, error("An account with this email already exists.")); return; } throw ex; }
    json(e, 201, "{\"accountCreated\":true}");
  }

  private static void login(HttpExchange e) throws IOException, GeneralSecurityException {
    Document body = requestDocument(e);
    String email = stringField(body, "email").trim().toLowerCase(Locale.ROOT);
    String password = stringField(body, "password");
    String requestedRole = stringField(body, "portal").isBlank() ? "resident" : stringField(body, "portal");
    if (!Set.of("resident", "municipal").contains(requestedRole)) throw new IllegalArgumentException("Invalid sign-in type.");
    Document user = users.find(eq("email", email)).first();
    if (user == null || !passwordMatches(password, user.getString("password_salt"), user.getString("password_hash"))) {
      json(e, 401, error("Email or password is incorrect."));
      return;
    }
    String accountRole = Objects.toString(user.get("role"), "resident");
    if (!requestedRole.equals(accountRole)) {
      json(e, 403, error(requestedRole.equals("municipal") ? "This account is not authorized for corporation employee access." : "Use the corporation employee sign-in for this account."));
      return;
    }
    createSession(e, user);
  }

  private static void createSession(HttpExchange e, Document user) throws IOException {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String role = Objects.toString(user.get("role"), "resident");
    sessions.put(token, new SessionInfo(user.getString("_id"), user.getString("name"), role, System.currentTimeMillis() + SESSION_SECONDS * 1000L));
    e.getResponseHeaders().add("Set-Cookie", "civicsignal_session=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + SESSION_SECONDS);
    json(e, 200, "{\"authenticated\":true,\"name\":" + quote(user.getString("name")) + ",\"role\":" + quote(role) + "}");
  }

  private static void authSession(HttpExchange e) throws IOException {
    SessionInfo session = currentSession(e);
    json(e, 200, session == null ? "{\"authenticated\":false}" : "{\"authenticated\":true,\"name\":" + quote(session.name()) + ",\"role\":" + quote(session.role()) + "}");
  }

  private static void logout(HttpExchange e) throws IOException {
    String token = sessionToken(e);
    if (token != null) sessions.remove(token);
    e.getResponseHeaders().add("Set-Cookie", "civicsignal_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    json(e, 200, "{\"authenticated\":false}");
  }

  private static SessionInfo requireSession(HttpExchange e) throws IOException {
    SessionInfo session = currentSession(e);
    if (session == null) json(e, 401, error("Sign in to continue."));
    return session;
  }

  private static SessionInfo requireMunicipalSession(HttpExchange e) throws IOException {
    SessionInfo session = currentSession(e);
    if (session == null) { json(e, 401, error("Sign in with a corporation employee account.")); return null; }
    if (!"municipal".equals(session.role())) { json(e, 403, error("Corporation employee access is required.")); return null; }
    return session;
  }

  private static SessionInfo currentSession(HttpExchange e) {
    String token = sessionToken(e);
    if (token == null) return null;
    SessionInfo session = sessions.get(token);
    if (session != null && session.expiresAt() <= System.currentTimeMillis()) { sessions.remove(token); return null; }
    return session;
  }

  private static String sessionToken(HttpExchange e) {
    String cookie = e.getRequestHeaders().getFirst("Cookie");
    if (cookie == null) return null;
    for (String entry : cookie.split(";")) {
      String item = entry.trim();
      if (item.startsWith("civicsignal_session=")) return item.substring("civicsignal_session=".length());
    }
    return null;
  }

  private static Document requestDocument(HttpExchange e) throws IOException {
    try { return Document.parse(readBody(e)); }
    catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid request data."); }
  }

  private static String stringField(Document body, String key) {
    Object value = body.get(key);
    return value instanceof String ? (String) value : "";
  }

  private static String newSalt() {
    byte[] salt = new byte[16];
    SECURE_RANDOM.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt);
  }

  private static String passwordHash(String password, String saltText) throws GeneralSecurityException {
    byte[] salt = Base64.getDecoder().decode(saltText);
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, 256);
    try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
    finally { spec.clearPassword(); }
  }

  private static boolean passwordMatches(String password, String salt, String expectedHash) throws GeneralSecurityException {
    if (salt == null || expectedHash == null) return false;
    byte[] actual = Base64.getDecoder().decode(passwordHash(password, salt));
    byte[] expected = Base64.getDecoder().decode(expectedHash);
    return MessageDigest.isEqual(actual, expected);
  }

  private record SessionInfo(String userId, String name, String role, long expiresAt) {}

  private static void listReports(HttpExchange e) throws Exception { String voterId=voterId(e); StringBuilder out=new StringBuilder("["); boolean first=true; for(Document report:reports.find().sort(descending("_id"))){if(!first)out.append(',');first=false;out.append(reportJson(report,voterId));} out.append(']'); json(e,200,out.toString()); }
  private static void listAdminReports(HttpExchange e) throws IOException {
    StringBuilder out = new StringBuilder("[");
    boolean first = true;
    for (Document report : reports.find().sort(descending("_id"))) {
      if (!first) out.append(',');
      first = false;
      out.append(reportJson(report, null));
    }
    out.append(']');
    json(e, 200, out.toString());
  }

  private static void updateReportStatus(HttpExchange e, long id, SessionInfo staff) throws IOException {
    String status = stringField(requestDocument(e), "status").trim().toLowerCase(Locale.ROOT);
    if (!Set.of("queued", "under review", "sent", "resolved").contains(status)) {
      throw new IllegalArgumentException("Choose queued, under review, sent, or resolved.");
    }
    String updatedAt = Instant.now().toString();
    Document report = reports.findOneAndUpdate(eq("_id", id), combine(set("municipal_status", status),
        set("status_updated_at", updatedAt), set("status_updated_by", staff.name())),
        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    if (report == null) { json(e, 404, error("Report not found.")); return; }
    json(e, 200, reportJson(report, null));
  }
  private static void like(HttpExchange e,long id) throws Exception {
    String voterId=voterId(e);
    if(voterId==null) throw new IllegalArgumentException("A valid X-Voter-Id header is required.");
    if(reports.find(eq("_id", id)).first()==null){json(e,404,error("Report not found"));return;}
    boolean liked=reports.updateOne(and(eq("_id",id),ne("liked_by",voterId)),
        combine(addToSet("liked_by",voterId),inc("likes_count",1))).getModifiedCount()==1;
    if(!liked) reports.updateOne(and(eq("_id",id),eq("liked_by",voterId)),
        combine(pull("liked_by",voterId),inc("likes_count",-1)));
    Document report=reports.find(eq("_id",id)).first();
    json(e,200,"{\"likes\":"+report.getInteger("likes_count",0)+",\"liked\":"+liked+"}");
  }
  private static void comment(HttpExchange e,long id) throws Exception { String body=jsonValue(readBody(e),"body"); if(body==null||body.isBlank())throw new IllegalArgumentException("Comment cannot be empty."); body=body.trim(); if(body.length()>500)body=body.substring(0,500); Document newComment=new Document("author","Neighbour").append("body",body).append("created_at",Instant.now().toString()); if(reports.findOneAndUpdate(eq("_id",id),push("comments",newComment))==null){json(e,404,error("Report not found"));return;} json(e,201,commentJson(newComment)); }

  private static void createReport(HttpExchange e) throws Exception {
    Multipart form = multipart(e); String type=form.fields.getOrDefault("issueType","").trim(), location=form.fields.getOrDefault("location","").trim(), description=form.fields.getOrDefault("description","").trim();
    if(type.isEmpty()||location.isEmpty()||description.isEmpty()) throw new IllegalArgumentException("Issue type, location, and description are required.");
    Double latitude=coordinate(form.fields.get("latitude"),-90,90), longitude=coordinate(form.fields.get("longitude"),-180,180);
    if((latitude==null)!=(longitude==null)) throw new IllegalArgumentException("Both GPS coordinates are required to map a report.");
    Path image=null; String imageUrl=null;
    if(form.file!=null){ if(!IMAGE_EXTENSIONS.containsKey(form.file.type)||form.file.data.length>MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Use a JPG, PNG, or WEBP image up to 10MB."); image=UPLOADS.resolve(UUID.randomUUID()+IMAGE_EXTENSIONS.get(form.file.type)); Files.write(image,form.file.data); imageUrl="uploads/"+image.getFileName(); }
    long id=nextReportId(); String now=Instant.now().toString();
    Document report=new Document("_id",id).append("issue_type",type).append("location",location)
        .append("description",description).append("image_url",imageUrl).append("created_at",now)
        .append("municipal_status","queued").append("liked_by",List.of()).append("likes_count",0).append("comments",List.of());
    if(latitude!=null) report.append("latitude",latitude).append("longitude",longitude);
    reports.insertOne(report);
    String status=sendToMunicipality(type,location,description,image);
    reports.updateOne(eq("_id",id),set("municipal_status",status));
    report.put("municipal_status",status);
    json(e,201,reportJson(report,null));
  }

  private static String reportJson(Document report, String voterId) {
    List<String> likedBy=report.getList("liked_by",String.class,List.of());
    List<Document> comments=report.getList("comments",Document.class,List.of());
    StringBuilder commentJson=new StringBuilder("[");
    for(int i=0;i<comments.size();i++){if(i>0)commentJson.append(',');commentJson.append(commentJson(comments.get(i)));}
    commentJson.append(']');
    Object imageUrl=report.get("image_url");
    return "{\"id\":"+report.getLong("_id")+",\"issue_type\":"+quote(report.getString("issue_type"))+",\"location\":"+quote(report.getString("location"))+",\"description\":"+quote(report.getString("description"))+",\"image_url\":"+(imageUrl==null?"null":quote((String)imageUrl))+",\"created_at\":"+quote(report.getString("created_at"))+",\"municipal_status\":"+quote(report.getString("municipal_status"))+",\"status_updated_at\":"+(report.getString("status_updated_at")==null?"null":quote(report.getString("status_updated_at")))+",\"status_updated_by\":"+(report.getString("status_updated_by")==null?"null":quote(report.getString("status_updated_by")))+",\"latitude\":"+numberJson(report.get("latitude"))+",\"longitude\":"+numberJson(report.get("longitude"))+",\"likes\":"+report.getInteger("likes_count",0)+",\"liked\":"+(voterId!=null&&likedBy.contains(voterId))+",\"comments\":"+commentJson+"}";
  }
  private static Double coordinate(String value,double min,double max){if(value==null||value.isBlank())return null;try{double coordinate=Double.parseDouble(value);if(!Double.isFinite(coordinate)||coordinate<min||coordinate>max)throw new IllegalArgumentException("Invalid GPS coordinates.");return coordinate;}catch(NumberFormatException ex){throw new IllegalArgumentException("Invalid GPS coordinates.");}}
  private static String numberJson(Object value){return value instanceof Number&&Double.isFinite(((Number)value).doubleValue())?value.toString():"null";}
  private static String commentJson(Document comment){return "{\"author\":"+quote(comment.getString("author"))+",\"body\":"+quote(comment.getString("body"))+",\"created_at\":"+quote(comment.getString("created_at"))+"}";}
  private static String voterId(HttpExchange e){String value=e.getRequestHeaders().getFirst("X-Voter-Id");if(value==null||value.isBlank())return null;try{return UUID.fromString(value).toString();}catch(IllegalArgumentException ex){throw new IllegalArgumentException("Invalid X-Voter-Id header.");}}

  private static Multipart multipart(HttpExchange e)throws IOException { int length=Integer.parseInt(e.getRequestHeaders().getFirst("Content-length") == null ? "0" : e.getRequestHeaders().getFirst("Content-length"));if(length>MAX_UPLOAD_BYTES+100000)throw new IllegalArgumentException("Photo must be 10MB or smaller."); String contentType=Optional.ofNullable(e.getRequestHeaders().getFirst("Content-type")).orElse("");Matcher b=Pattern.compile("boundary=([^;]+)").matcher(contentType);if(!b.find())throw new IllegalArgumentException("Expected multipart form data.");String boundary="--"+b.group(1).replace("\"","");String raw=new String(readBodyBytes(e),StandardCharsets.ISO_8859_1);Multipart result=new Multipart();for(String part:raw.split(Pattern.quote(boundary))){int split=part.indexOf("\r\n\r\n");if(split<0)continue;String headers=part.substring(0,split), data=part.substring(split+4);if(data.endsWith("\r\n"))data=data.substring(0,data.length()-2);Matcher name=Pattern.compile("name=\"([^\"]+)\"").matcher(headers);if(!name.find())continue;Matcher filename=Pattern.compile("filename=\"([^\"]*)\"").matcher(headers);if(filename.find()){Matcher type=Pattern.compile("Content-Type: ([^\\r\\n]+)",Pattern.CASE_INSENSITIVE).matcher(headers);result.file=new Upload(type.find()?type.group(1).trim():"application/octet-stream",data.getBytes(StandardCharsets.ISO_8859_1));}else result.fields.put(name.group(1),data); }return result; }
  private static void serveStatic(HttpExchange e,String request)throws IOException { if(!"GET".equals(e.getRequestMethod())&& !"HEAD".equals(e.getRequestMethod())){json(e,405,error("Method not allowed"));return;}String clean=request.equals("/")?"index.html":URLDecoder.decode(request.substring(1),StandardCharsets.UTF_8);Path base=clean.startsWith("uploads/")?UPLOADS:STATIC_ROOT;String relative=clean.startsWith("uploads/")?clean.substring("uploads/".length()):clean;Path file=base.resolve(relative).normalize();if(!file.startsWith(base)||!isPublicFile(clean)||!Files.isRegularFile(file)){json(e,404,error("Not found"));return;}byte[] data=Files.readAllBytes(file);e.getResponseHeaders().set("Content-Type",contentType(file));e.sendResponseHeaders(200,"HEAD".equals(e.getRequestMethod())?-1:data.length);if(!"HEAD".equals(e.getRequestMethod()))e.getResponseBody().write(data);e.close(); }
  private static boolean isPublicFile(String clean) { return clean.equals("index.html") || clean.matches("[A-Za-z0-9_-]+\\.(css|js|ico|png|jpg|jpeg|webp|svg)") || clean.matches("(assets|uploads)/[A-Za-z0-9._-]+\\.(png|jpg|jpeg|webp|svg)"); }
  private static String contentType(Path file) throws IOException { String name=file.getFileName().toString().toLowerCase(Locale.ROOT); if(name.endsWith(".css"))return "text/css; charset=utf-8";if(name.endsWith(".js"))return "text/javascript; charset=utf-8";if(name.endsWith(".svg"))return "image/svg+xml";return Optional.ofNullable(Files.probeContentType(file)).orElse("application/octet-stream"); }
  private static void json(HttpExchange e,int status,String body)throws IOException{byte[] data=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);e.close();}
  private static String readBody(HttpExchange e)throws IOException{return new String(readBodyBytes(e),StandardCharsets.UTF_8);} private static byte[] readBodyBytes(HttpExchange e)throws IOException{return e.getRequestBody().readAllBytes();}
  private static String jsonValue(String source,String key){Matcher m=Pattern.compile("\""+Pattern.quote(key)+"\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(source);if(!m.find())return null;return m.group(1).replace("\\\"","\"").replace("\\n","\n").replace("\\\\","\\");} private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";} private static String error(String message){return "{\"error\":"+quote(message)+"}";} private static String env(String key,String fallback){String value=System.getenv(key);if(value==null)value=System.getProperty("civicsignal.env."+key);return value==null||value.isBlank()?fallback:value;}
  private static String sendToMunicipality(String type,String location,String description,Path image){String host=System.getenv("SMTP_HOST"),to=System.getenv("MUNICIPAL_EMAIL");if(host==null||to==null)return "queued";try{Properties p=new Properties();p.put("mail.smtp.host",host);p.put("mail.smtp.port",env("SMTP_PORT","587"));p.put("mail.smtp.starttls.enable",env("SMTP_TLS","true"));String user=System.getenv("SMTP_USER");String password=System.getenv("SMTP_PASSWORD");Session s=Session.getInstance(p,user==null?null:new Authenticator(){protected PasswordAuthentication getPasswordAuthentication(){return new PasswordAuthentication(user,password==null?"":password);}});MimeMessage message=new MimeMessage(s);message.setFrom(new InternetAddress(env("SMTP_FROM",user==null?"civicsignal@localhost":user)));message.setRecipients(Message.RecipientType.TO,to);message.setSubject("CivicSignal report: "+type+" — "+location);MimeBodyPart text=new MimeBodyPart();text.setText("New civic issue report\n\nType: "+type+"\nLocation: "+location+"\n\nDescription:\n"+description);jakarta.mail.Multipart multi=new MimeMultipart();multi.addBodyPart(text);if(image!=null){MimeBodyPart photo=new MimeBodyPart();photo.attachFile(image.toFile());multi.addBodyPart(photo);}message.setContent(multi);Transport.send(message);return "sent";}catch(Exception ignored){return "queued";}}
  private static final class Multipart { final Map<String,String> fields=new HashMap<>(); Upload file; } private record Upload(String type,byte[] data) {}
}
