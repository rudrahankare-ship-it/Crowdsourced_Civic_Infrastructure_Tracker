package org.civicsignal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.*;

/** Standalone CivicSignal backend. It serves the static site and the reports API. */
public final class CivicSignalServer {
  private static final Path ROOT = Path.of("").toAbsolutePath();
  private static final Path STATIC_ROOT = ROOT.resolve("frontend");
  private static final Path UPLOADS = ROOT.resolve("uploads");
  private static final String DB_URL = "jdbc:sqlite:" + ROOT.resolve("civicsignal.db");
  private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
  private static final Map<String, String> IMAGE_EXTENSIONS = Map.of("image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp");
  private static final Pattern ACTION = Pattern.compile("^/api/reports/(\\d+)/(like|comments)$");

  public static void main(String[] args) throws Exception {
    initializeDatabase();
    int port = Integer.parseInt(env("PORT", "8000"));
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", CivicSignalServer::handle);
    server.setExecutor(Executors.newCachedThreadPool());
    System.out.println("CivicSignal running at http://localhost:" + port);
    server.start();
  }

  private static Connection db() throws SQLException { return DriverManager.getConnection(DB_URL); }

  private static void initializeDatabase() throws Exception {
    Files.createDirectories(UPLOADS);
    try (Connection c = db(); Statement s = c.createStatement()) {
      s.executeUpdate("PRAGMA foreign_keys = ON");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, issue_type TEXT NOT NULL, location TEXT NOT NULL, description TEXT NOT NULL, image_url TEXT, created_at TEXT NOT NULL, municipal_status TEXT NOT NULL DEFAULT 'queued')");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS likes (id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL REFERENCES reports(id) ON DELETE CASCADE, created_at TEXT NOT NULL)");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS comments (id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL REFERENCES reports(id) ON DELETE CASCADE, author TEXT NOT NULL DEFAULT 'Neighbour', body TEXT NOT NULL, created_at TEXT NOT NULL)");
      try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM reports")) {
        if (rs.next() && rs.getInt(1) == 0) seed(c);
      }
    }
  }

  private static void seed(Connection c) throws SQLException {
    String now = Instant.now().toString();
    try (PreparedStatement p = c.prepareStatement("INSERT INTO reports (issue_type,location,description,image_url,created_at,municipal_status) VALUES (?,?,?,?,?,?)")) {
      String[][] rows = {{"Road damage", "Gandhi Nagar, Ward 12", "Large pothole causing traffic to swerve near the school gate", "assets/pothole-report.png", "sent"}, {"Streetlight", "Aundh, Ward 8", "Three streetlights out on the walking path by the park", "assets/streetlight-report.png", "under review"}, {"Footpath", "Kothrud, Ward 4", "Broken pavement tiles make this bus stop inaccessible", "assets/footpath-report.png", "needs attention"}};
      for (String[] r : rows) { for (int i=0; i<3; i++) p.setString(i+1, r[i]); p.setString(4,r[3]); p.setString(5,now); p.setString(6,r[4]); p.addBatch(); } p.executeBatch();
    }
  }

  private static void handle(HttpExchange e) throws IOException {
    try {
      String path = e.getRequestURI().getPath();
      if ("GET".equals(e.getRequestMethod()) && "/api/reports".equals(path)) { listReports(e); return; }
      if ("POST".equals(e.getRequestMethod()) && "/api/reports".equals(path)) { createReport(e); return; }
      Matcher match = ACTION.matcher(path);
      if ("POST".equals(e.getRequestMethod()) && match.matches()) { if ("like".equals(match.group(2))) like(e, Long.parseLong(match.group(1))); else comment(e, Long.parseLong(match.group(1))); return; }
      if (path.startsWith("/api/")) { json(e, 404, "{\"error\":\"Not found\"}"); return; }
      serveStatic(e, path);
    } catch (IllegalArgumentException ex) { json(e, 400, error(ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); json(e, 500, error("Internal server error")); }
  }

  private static void listReports(HttpExchange e) throws Exception { StringBuilder out=new StringBuilder("["); try(Connection c=db(); Statement s=c.createStatement(); ResultSet rs=s.executeQuery("SELECT * FROM reports ORDER BY id DESC")){ boolean first=true; while(rs.next()){if(!first)out.append(','); first=false; out.append(reportJson(c,rs));}} out.append(']'); json(e,200,out.toString()); }
  private static void like(HttpExchange e,long id) throws Exception { try(Connection c=db(); PreparedStatement p=c.prepareStatement("INSERT INTO likes (report_id,created_at) VALUES (?,?)")){p.setLong(1,id);p.setString(2,Instant.now().toString());p.executeUpdate();json(e,200,"{\"likes\":"+likes(c,id)+"}");} }
  private static void comment(HttpExchange e,long id) throws Exception { String body=jsonValue(readBody(e),"body"); if(body==null||body.isBlank())throw new IllegalArgumentException("Comment cannot be empty."); body=body.trim(); if(body.length()>500)body=body.substring(0,500); try(Connection c=db();PreparedStatement p=c.prepareStatement("INSERT INTO comments (report_id,author,body,created_at) VALUES (?,?,?,?)")){p.setLong(1,id);p.setString(2,"Neighbour");p.setString(3,body);p.setString(4,Instant.now().toString());p.executeUpdate();} json(e,201,"{\"author\":\"Neighbour\",\"body\":"+quote(body)+"}"); }

  private static void createReport(HttpExchange e) throws Exception {
    Multipart form = multipart(e); String type=form.fields.getOrDefault("issueType","").trim(), location=form.fields.getOrDefault("location","").trim(), description=form.fields.getOrDefault("description","").trim();
    if(type.isEmpty()||location.isEmpty()||description.isEmpty()) throw new IllegalArgumentException("Issue type, location, and description are required.");
    Path image=null; String imageUrl=null;
    if(form.file!=null){ if(!IMAGE_EXTENSIONS.containsKey(form.file.type)||form.file.data.length>MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Use a JPG, PNG, or WEBP image up to 10MB."); image=UPLOADS.resolve(UUID.randomUUID()+IMAGE_EXTENSIONS.get(form.file.type)); Files.write(image,form.file.data); imageUrl="uploads/"+image.getFileName(); }
    long id; String now=Instant.now().toString();
    try(Connection c=db();PreparedStatement p=c.prepareStatement("INSERT INTO reports (issue_type,location,description,image_url,created_at,municipal_status) VALUES (?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
      p.setString(1,type);p.setString(2,location);p.setString(3,description);p.setString(4,imageUrl);p.setString(5,now);p.setString(6,"queued");p.executeUpdate();try(ResultSet k=p.getGeneratedKeys()){k.next();id=k.getLong(1);} String status=sendToMunicipality(type,location,description,image); try(PreparedStatement u=c.prepareStatement("UPDATE reports SET municipal_status=? WHERE id=?")){u.setString(1,status);u.setLong(2,id);u.executeUpdate();} try(PreparedStatement q=c.prepareStatement("SELECT * FROM reports WHERE id=?")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){r.next();json(e,201,reportJson(c,r));}}
    }
  }

  private static String reportJson(Connection c, ResultSet r) throws SQLException { long id=r.getLong("id"); StringBuilder comments=new StringBuilder("[");try(PreparedStatement p=c.prepareStatement("SELECT author,body,created_at FROM comments WHERE report_id=? ORDER BY id")){p.setLong(1,id);try(ResultSet rs=p.executeQuery()){boolean first=true;while(rs.next()){if(!first)comments.append(',');first=false;comments.append("{\"author\":").append(quote(rs.getString(1))).append(",\"body\":").append(quote(rs.getString(2))).append(",\"created_at\":").append(quote(rs.getString(3))).append('}');}}} return "{\"id\":"+id+",\"issue_type\":"+quote(r.getString("issue_type"))+",\"location\":"+quote(r.getString("location"))+",\"description\":"+quote(r.getString("description"))+",\"image_url\":"+(r.getString("image_url")==null?"null":quote(r.getString("image_url")))+",\"created_at\":"+quote(r.getString("created_at"))+",\"municipal_status\":"+quote(r.getString("municipal_status"))+",\"likes\":"+likes(c,id)+",\"comments\":"+comments+"]}"; }
  private static int likes(Connection c,long id)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM likes WHERE report_id=?")){p.setLong(1,id);try(ResultSet r=p.executeQuery()){r.next();return r.getInt(1);}}}

  private static Multipart multipart(HttpExchange e)throws IOException { int length=Integer.parseInt(e.getRequestHeaders().getFirst("Content-length") == null ? "0" : e.getRequestHeaders().getFirst("Content-length"));if(length>MAX_UPLOAD_BYTES+100000)throw new IllegalArgumentException("Photo must be 10MB or smaller."); String contentType=Optional.ofNullable(e.getRequestHeaders().getFirst("Content-type")).orElse("");Matcher b=Pattern.compile("boundary=([^;]+)").matcher(contentType);if(!b.find())throw new IllegalArgumentException("Expected multipart form data.");String boundary="--"+b.group(1).replace("\"","");String raw=new String(readBodyBytes(e),StandardCharsets.ISO_8859_1);Multipart result=new Multipart();for(String part:raw.split(Pattern.quote(boundary))){int split=part.indexOf("\r\n\r\n");if(split<0)continue;String headers=part.substring(0,split), data=part.substring(split+4);if(data.endsWith("\r\n"))data=data.substring(0,data.length()-2);Matcher name=Pattern.compile("name=\"([^\"]+)\"").matcher(headers);if(!name.find())continue;Matcher filename=Pattern.compile("filename=\"([^\"]*)\"").matcher(headers);if(filename.find()){Matcher type=Pattern.compile("Content-Type: ([^\\r\\n]+)",Pattern.CASE_INSENSITIVE).matcher(headers);result.file=new Upload(type.find()?type.group(1).trim():"application/octet-stream",data.getBytes(StandardCharsets.ISO_8859_1));}else result.fields.put(name.group(1),data); }return result; }
  private static void serveStatic(HttpExchange e,String request)throws IOException { if(!"GET".equals(e.getRequestMethod())&&!"HEAD".equals(e.getRequestMethod())){json(e,405,error("Method not allowed"));return;}String clean=request.equals("/")?"index.html":URLDecoder.decode(request.substring(1),StandardCharsets.UTF_8);Path file=STATIC_ROOT.resolve(clean).normalize();if(!file.startsWith(STATIC_ROOT)||!Files.isRegularFile(file)){json(e,404,error("Not found"));return;}byte[] data=Files.readAllBytes(file);e.getResponseHeaders().set("Content-Type",Optional.ofNullable(Files.probeContentType(file)).orElse("application/octet-stream"));e.sendResponseHeaders(200,"HEAD".equals(e.getRequestMethod())?-1:data.length);if(!"HEAD".equals(e.getRequestMethod()))e.getResponseBody().write(data);e.close(); }
  private static void json(HttpExchange e,int status,String body)throws IOException{byte[] data=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);e.close();}
  private static String readBody(HttpExchange e)throws IOException{return new String(readBodyBytes(e),StandardCharsets.UTF_8);} private static byte[] readBodyBytes(HttpExchange e)throws IOException{return e.getRequestBody().readAllBytes();}
  private static String jsonValue(String source,String key){Matcher m=Pattern.compile("\""+Pattern.quote(key)+"\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(source);if(!m.find())return null;return m.group(1).replace("\\\"","\"").replace("\\n","\n").replace("\\\\","\\");} private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";} private static String error(String message){return "{\"error\":"+quote(message)+"}";} private static String env(String key,String fallback){String value=System.getenv(key);return value==null||value.isBlank()?fallback:value;}
  private static String sendToMunicipality(String type,String location,String description,Path image){String host=System.getenv("SMTP_HOST"),to=System.getenv("MUNICIPAL_EMAIL");if(host==null||to==null)return "queued";try{Properties p=new Properties();p.put("mail.smtp.host",host);p.put("mail.smtp.port",env("SMTP_PORT","587"));p.put("mail.smtp.starttls.enable",env("SMTP_TLS","true"));String user=System.getenv("SMTP_USER"),password=env("SMTP_PASSWORD","");Session s=Session.getInstance(p,user==null?null:new Authenticator(){protected PasswordAuthentication getPasswordAuthentication(){return new PasswordAuthentication(user,password);}});MimeMessage message=new MimeMessage(s);message.setFrom(new InternetAddress(env("SMTP_FROM",user==null?"civicsignal@localhost":user)));message.setRecipients(Message.RecipientType.TO,to);message.setSubject("CivicSignal report: "+type+" — "+location);MimeBodyPart text=new MimeBodyPart();text.setText("New civic issue report\n\nType: "+type+"\nLocation: "+location+"\n\nDescription:\n"+description);jakarta.mail.Multipart multi=new MimeMultipart();multi.addBodyPart(text);if(image!=null){MimeBodyPart photo=new MimeBodyPart();photo.attachFile(image.toFile());multi.addBodyPart(photo);}message.setContent(multi);Transport.send(message);return "sent";}catch(Exception ignored){return "queued";}}
  private static final class Multipart { final Map<String,String> fields=new HashMap<>(); Upload file; } private record Upload(String type,byte[] data) {}
}
