# CampusOne — Java / Spring Boot Conversion

This folder is the Java/Spring Boot migration of the supplied Flask CampusOne project.

## Stack
- Java 17
- Spring Boot 3.5.6
- Spring JDBC
- SQLite
- Jinjava (keeps the existing Jinja-style HTML templates)
- Apache PDFBox (server-side PDF page counting)
- Spring Security Crypto (password hashing)
- Bouncy Castle (compatibility with Werkzeug scrypt hashes)

## Run in VS Code / terminal

1. Install **JDK 17+** and **Maven 3.9+**.
2. Open this folder in VS Code.
3. Run:

```bash
mvn spring-boot:run
```

4. Open `http://localhost:8080`

To build a runnable JAR:

```bash
mvn clean package
java -jar target/campusone-1.0.0.jar
```

## Demo accounts

- Admin: `admin@campusone.com` / `admin123`
- Canteen: `canteen@campusone.com` / `canteen123`
- Complaints: `complaints@campusone.com` / `complaints123`
- Store: `store@campusone.com` / `store123`
- Printing: `printing@campusone.com` / `printing123`
- Student: `student@campusone.com` / `student123`

## Important
- The existing `campusone.db` is retained.
- Existing Flask uploads are retained.
- The HTML/CSS/JS UI is retained rather than redesigned.
- The print backend recalculates PDF page count using PDFBox and calculates B&W at ₹2/page and Colour at ₹5/page.
- Five-digit store/canteen order numbers are retained.
- Store/canteen stock decreases when an order is placed.
- Payment screenshots, complaints, printing files, order history, restore, and department-admin roles are included.
- SQLite is configured with a single Hikari connection because SQLite is file-based.

## Migration note
The original project used Flask sessions. This Java version uses `HttpSession`. The original Jinja-style templates are rendered through Jinjava, with Flask `url_for` paths converted to ordinary Spring paths during migration.

The original Python project remains available in the supplied ZIP if you need to compare behavior.

The complaints form continues to submit to `/complaints`, matching the original Flask URL.
