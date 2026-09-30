package com.campusone;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DatabaseInitializer implements CommandLineRunner {
    private final JdbcTemplate db;

    public DatabaseInitializer(JdbcTemplate db) { this.db = db; }

    @Override
    public void run(String... args) {
        createTables();
        migrate();
        seedAccounts();
        seedStore();
        seedCanteen();
    }

    private void createTables() {
        db.execute(""" 
            CREATE TABLE IF NOT EXISTS users (
              id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
              email TEXT UNIQUE NOT NULL, password TEXT NOT NULL,
              role TEXT NOT NULL DEFAULT 'student'
            )""");
        db.execute("""
            CREATE TABLE IF NOT EXISTS complaints (
              id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL,
              category TEXT NOT NULL, location TEXT NOT NULL, description TEXT NOT NULL,
              image TEXT, status TEXT NOT NULL DEFAULT 'Submitted',
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )""");
        db.execute("""
            CREATE TABLE IF NOT EXISTS products (
              id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
              price REAL NOT NULL, stock INTEGER NOT NULL DEFAULT 0
            )""");
        db.execute("""
            CREATE TABLE IF NOT EXISTS canteen_items (
              id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
              category TEXT NOT NULL, price REAL NOT NULL,
              available INTEGER NOT NULL DEFAULT 1, stock INTEGER NOT NULL DEFAULT 50
            )""");
        db.execute("""
            CREATE TABLE IF NOT EXISTS canteen_orders (
              id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL,
              student_name TEXT, department TEXT, order_number TEXT UNIQUE NOT NULL,
              total_amount REAL NOT NULL, status TEXT NOT NULL DEFAULT 'Received',
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, is_archived INTEGER NOT NULL DEFAULT 0,
              payment_status TEXT NOT NULL DEFAULT 'Pending', payment_screenshot TEXT
            )""");
        db.execute("""
            CREATE TABLE IF NOT EXISTS canteen_order_items (
              id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL,
              item_id INTEGER NOT NULL, quantity INTEGER NOT NULL DEFAULT 1, price REAL NOT NULL
            )""");
        db.execute("""
            CREATE TABLE IF NOT EXISTS print_orders (
              id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL,
              student_name TEXT, department TEXT, filename TEXT NOT NULL,
              copies INTEGER NOT NULL DEFAULT 1, page_count INTEGER NOT NULL DEFAULT 1,
              color TEXT NOT NULL, sides TEXT NOT NULL, total_amount REAL NOT NULL DEFAULT 0,
              status TEXT NOT NULL DEFAULT 'Received', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              is_archived INTEGER NOT NULL DEFAULT 0, payment_status TEXT NOT NULL DEFAULT 'Pending',
              payment_screenshot TEXT
            )""");
        db.execute("""
            CREATE TABLE IF NOT EXISTS store_orders (
              id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL,
              student_name TEXT, department TEXT, order_number TEXT UNIQUE NOT NULL,
              total_amount REAL NOT NULL, items TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'Received', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              is_archived INTEGER NOT NULL DEFAULT 0, payment_status TEXT NOT NULL DEFAULT 'Pending',
              payment_screenshot TEXT
            )""");
    }

    private boolean hasColumn(String table, String column) {
        return !db.queryForList("PRAGMA table_info(" + table + ")").stream()
                .filter(r -> column.equals(String.valueOf(r.get("name")))).toList().isEmpty();
    }

    private void addColumn(String table, String column, String definition) {
        if (!hasColumn(table, column)) db.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void migrate() {
        for (String t : List.of("canteen_orders", "store_orders", "print_orders")) {
            addColumn(t, "student_name", "TEXT");
            addColumn(t, "department", "TEXT");
            addColumn(t, "is_archived", "INTEGER NOT NULL DEFAULT 0");
            addColumn(t, "payment_status", "TEXT NOT NULL DEFAULT 'Pending'");
            addColumn(t, "payment_screenshot", "TEXT");
        }
        addColumn("print_orders", "total_amount", "REAL NOT NULL DEFAULT 0");
        addColumn("print_orders", "page_count", "INTEGER NOT NULL DEFAULT 1");
        addColumn("canteen_items", "stock", "INTEGER NOT NULL DEFAULT 50");
    }

    private void seedAccounts() {
        seedUser("CampusOne Admin", "admin@campusone.com", "admin123", "admin");
        seedUser("Canteen Admin", "canteen@campusone.com", "canteen123", "canteen_admin");
        seedUser("Complaint Admin", "complaints@campusone.com", "complaints123", "complaint_admin");
        seedUser("Store Admin", "store@campusone.com", "store123", "store_admin");
        seedUser("Printing Admin", "printing@campusone.com", "printing123", "printing_admin");
        seedUser("CampusOne Student", "student@campusone.com", "student123", "student");
    }

    private void seedUser(String name, String email, String password, String role) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email);
        if (count != null && count == 0) {
            db.update("INSERT INTO users(name,email,password,role) VALUES(?,?,?,?)",
                    name, email, PasswordUtil.hash(password), role);
        }
    }

    private void seedStore() {
        Object[][] items = {
          {"College Notebook",60,50},{"Record Book",85,40},{"Blue Pen",10,100},{"College File",35,60},
          {"Black Pen",10,100},{"Red Pen",10,100},{"Pencil",8,100},{"Eraser",5,100},{"Sharpener",8,80},
          {"Ruler 15cm",12,70},{"Ruler 30cm",20,70},{"Geometry Box",75,40},{"Highlighter",25,60},
          {"Permanent Marker",30,60},{"Whiteboard Marker",25,60},{"Sticky Notes",35,50},{"A4 Paper Pack",120,30},
          {"Graph Paper Pack",45,40},{"Project File",45,50},{"Spiral Notebook",95,40},{"Drawing Book",70,40},
          {"Chart Paper",15,100},{"Craft Paper Set",55,40},{"Glue Stick",25,60},{"Fevicol Small",30,60},
          {"Scissors",45,40},{"Stapler",65,35},{"Staple Pins",20,70},{"Paper Clips",25,70},{"ID Card Holder",30,80}
        };
        for (Object[] x : items) {
            Integer c = db.queryForObject("SELECT COUNT(*) FROM products WHERE name=?", Integer.class, x[0]);
            if (c != null && c == 0) db.update("INSERT INTO products(name,price,stock) VALUES(?,?,?)", x);
        }
    }

    private void seedCanteen() {
        db.update("DELETE FROM canteen_items WHERE name IN ('Poori Masala','Vada','Fresh Lime','Lime Soda')");
        Object[][] items = {
          {"Tea","Drinks",12,1},{"Coffee","Drinks",15,1},{"Veg Sandwich","Snacks",35,1},{"Masala Dosa","Meals",50,1},
          {"Idli","Breakfast",30,1},{"Chapati","Meals",35,1},{"Veg Meals","Meals",80,1},{"Lemon Rice","Meals",45,1},
          {"Curd Rice","Meals",40,1},{"Tomato Rice","Meals",45,1},{"Veg Fried Rice","Meals",90,1},{"Veg Noodles","Meals",85,1},
          {"Samosa","Snacks",15,1},{"Pazhampori","Snacks",15,1},{"Parippu Vada","Snacks",12,1},{"Cutlet","Snacks",20,1},
          {"Banana Fry","Snacks",20,1},{"French Fries","Snacks",60,1},{"Veg Puff","Snacks",25,1},{"Egg Puff","Snacks",35,1},
          {"Chicken Roll","Snacks",70,1},{"Veg Roll","Snacks",50,1},{"Mango Juice","Drinks",45,1},{"Chocolate Milkshake","Drinks",80,1},
          {"Water Bottle","Drinks",20,1},{"Ice Cream Cup","Desserts",40,1}
        };
        for (Object[] x : items) {
            Integer c = db.queryForObject("SELECT COUNT(*) FROM canteen_items WHERE name=?", Integer.class, x[0]);
            if (c != null && c == 0) db.update("INSERT INTO canteen_items(name,category,price,available,stock) VALUES(?,?,?,?,50)", x);
        }
    }
}
