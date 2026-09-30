package com.campusone;

import com.hubspot.jinjava.Jinjava;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.io.*;
import java.nio.file.*;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Controller
public class CampusOneController {
    private final JdbcTemplate db;
    private final JinjavaRenderer renderer;
    private final Path root = Paths.get(System.getProperty("user.dir"));
    private final Path complaintDir = root.resolve("uploads/complaints");
    private final Path printingDir = root.resolve("uploads/printing");
    private final Path paymentDir = root.resolve("uploads/payments");

    public CampusOneController(JdbcTemplate db, JinjavaRenderer renderer) {
        this.db = db;
        this.renderer = renderer;
        try {
            Files.createDirectories(complaintDir);
            Files.createDirectories(printingDir);
            Files.createDirectories(paymentDir);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private Map<String,Object> sessionMap(HttpSession s) {
        Map<String,Object> m = new HashMap<>();
        Enumeration<String> names = s.getAttributeNames();
        while (names.hasMoreElements()) {
            String k = names.nextElement();
            m.put(k, s.getAttribute(k));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<String> flashes(HttpSession s, boolean consume) {
        Object o = s.getAttribute("flash_messages");
        List<String> list = o instanceof List ? new ArrayList<>((List<String>)o) : new ArrayList<>();
        if (consume) s.removeAttribute("flash_messages");
        return list;
    }

    private void flash(HttpSession s, String msg) {
        List<String> list = flashes(s, false);
        list.add(msg);
        s.setAttribute("flash_messages", list);
    }

    private ResponseEntity<?> html(String template, HttpSession s, Map<String,Object> model) {
        return ResponseEntity.ok(renderer.render(template, model, sessionMap(s), flashes(s, true)));
    }

    private ResponseEntity<?> redirect(String path) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, path).build();
    }

    private ResponseEntity<?> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access Denied");
    }

    private Integer uid(HttpSession s) {
        Object v=s.getAttribute("user_id");
        return v instanceof Number ? ((Number)v).intValue() : null;
    }

    private boolean logged(HttpSession s) { return uid(s) != null; }

    private boolean role(HttpSession s, String... roles) {
        Object r=s.getAttribute("role");
        if (r==null) return false;
        return Arrays.asList(roles).contains(r.toString());
    }

    private String safe(String name) {
        if (name==null) return "upload";
        name = Paths.get(name).getFileName().toString();
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String orderNumber(String table) {
        for (int i=0;i<100;i++) {
            String n=String.format("%05d", ThreadLocalRandom.current().nextInt(10000,100000));
            Integer c=db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE order_number=?",Integer.class,n);
            if (c==null || c==0) return n;
        }
        throw new IllegalStateException("Unable to generate a unique five-digit order number");
    }

    private String[] identity(HttpSession s, String studentName, String department) {
        String n=(studentName==null?"" : studentName.trim());
        if (n.isBlank()) n=String.valueOf(s.getAttribute("name"));
        String d=department==null?"":department.trim();
        return new String[]{n,d};
    }

    @GetMapping("/")
    @ResponseBody public ResponseEntity<?> home(HttpSession s) {
        return html("index.html",s,Map.of());
    }

    @GetMapping({"/register"})
    @ResponseBody public ResponseEntity<?> registerGet(HttpSession s) {
        return html("register.html",s,Map.of());
    }

    @PostMapping("/register")
    @ResponseBody public ResponseEntity<?> registerPost(
            @RequestParam String name,@RequestParam String email,@RequestParam String password,HttpSession s) {
        try {
            db.update("INSERT INTO users(name,email,password,role) VALUES(?,?,?,?)",
                    name.trim(),email.trim(),PasswordUtil.hash(password),"student");
            flash(s,"Registration successful. Please log in.");
            return redirect("/login");
        } catch(Exception e) {
            flash(s,"That email is already registered or the details are invalid.");
            return redirect("/register");
        }
    }

    @GetMapping("/student/login")
    @ResponseBody public ResponseEntity<?> studentLoginGet(HttpSession s){ return html("student_login.html",s,Map.of()); }

    @PostMapping("/student/login")
    @ResponseBody public ResponseEntity<?> studentLoginPost(@RequestParam String email,@RequestParam String password,HttpSession s){
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM users WHERE email=? AND role='student'",email.trim());
        if(!rows.isEmpty() && PasswordUtil.matches(password,Objects.toString(rows.get(0).get("password"),""))) {
            setSession(s,rows.get(0)); return redirect("/student/dashboard");
        }
        flash(s,"Invalid student email or password.");
        return redirect("/student/login");
    }

    @GetMapping("/login")
    @ResponseBody public ResponseEntity<?> loginGet(HttpSession s){ return html("login.html",s,Map.of()); }

    @PostMapping("/login")
    @ResponseBody public ResponseEntity<?> loginPost(@RequestParam String email,@RequestParam String password,HttpSession s){
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM users WHERE email=?",email.trim());
        if(!rows.isEmpty() && PasswordUtil.matches(password,Objects.toString(rows.get(0).get("password"),""))) {
            setSession(s,rows.get(0));
            String r=Objects.toString(rows.get(0).get("role"),"student");
            if(Set.of("admin","canteen_admin","complaint_admin","store_admin","printing_admin").contains(r)) return redirect("/admin/dashboard");
            if("staff".equals(r)) return redirect("/staff/dashboard");
            return redirect("/student/dashboard");
        }
        flash(s,"Invalid email or password.");
        return redirect("/login");
    }

    private void setSession(HttpSession s, Map<String,Object> u){
        s.setAttribute("user_id",((Number)u.get("id")).intValue());
        s.setAttribute("name",u.get("name"));
        s.setAttribute("role",u.get("role"));
    }

    @GetMapping("/logout")
    @ResponseBody public ResponseEntity<?> logout(HttpSession s){ s.invalidate(); return redirect("/"); }

    @GetMapping("/student/dashboard")
    @ResponseBody public ResponseEntity<?> studentDashboard(HttpSession s){
        if(!logged(s)) return redirect("/login");
        return html("student/dashboard.html",s,Map.of("name",s.getAttribute("name"),
                "store_cart_count",cart(s,"cart").size(),"canteen_cart_count",cart(s,"canteen_cart").size()));
    }

    @GetMapping("/complaints")
    @ResponseBody public ResponseEntity<?> complaints(
            @RequestParam(required=false) String category,@RequestParam(required=false) String location,
            @RequestParam(required=false) String description,@RequestPart(required=false) MultipartFile image,HttpSession s){
        if(!logged(s)) return redirect("/login");
        return html("student/complaints.html",s,Map.of("complaints",
                db.queryForList("SELECT * FROM complaints WHERE user_id=? ORDER BY created_at DESC",uid(s))));
    }

    @PostMapping("/complaints")
    @ResponseBody public ResponseEntity<?> complaintsSubmit(
            @RequestParam String category,@RequestParam String location,@RequestParam String description,
            @RequestPart(required=false) MultipartFile image,HttpSession s) {
        if(!logged(s)) return redirect("/login");
        String imageName=null;
        try {
            if(image!=null && !image.isEmpty()) {
                imageName=UUID.randomUUID().toString().replace("-","")+"_"+safe(image.getOriginalFilename());
                image.transferTo(complaintDir.resolve(imageName));
            }
            final String storedImageName = imageName;
            KeyHolder kh=new GeneratedKeyHolder();
            db.update(con -> {
                PreparedStatement ps=con.prepareStatement(
                    "INSERT INTO complaints(user_id,category,location,description,image,status) VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1,uid(s));ps.setString(2,category);ps.setString(3,location);
                ps.setString(4,description);ps.setString(5,storedImageName);ps.setString(6,"Submitted");return ps;
            },kh);
            return redirect("/complaints/success/"+kh.getKey().longValue());
        } catch(Exception e){ flash(s,"Could not submit the complaint."); return redirect("/complaints"); }
    }

    @GetMapping("/complaints/track")
    @ResponseBody public ResponseEntity<?> complaintTracking(HttpSession s){
        if(!logged(s)) return redirect("/login");
        return html("student/complaint_tracking.html",s,Map.of("complaints",
                db.queryForList("SELECT * FROM complaints WHERE user_id=? ORDER BY created_at DESC,id DESC",uid(s))));
    }

    @PostMapping("/complaints/cancel")
    @ResponseBody public ResponseEntity<?> cancelComplaint(@RequestParam Integer complaint_id,HttpSession s){
        if(!logged(s)||!"student".equals(s.getAttribute("role"))) return denied();
        List<Map<String,Object>> rows=db.queryForList("SELECT status FROM complaints WHERE id=? AND user_id=?",complaint_id,uid(s));
        if(rows.isEmpty()){flash(s,"Complaint could not be found.");return redirect("/complaints/track");}
        String st=Objects.toString(rows.get(0).get("status"),"");
        if(!Set.of("Submitted","Problem Noticed","In Progress","Problem Addressed","Closed").contains(st)){
            flash(s,"This complaint can no longer be cancelled."); return redirect("/complaints/track");
        }
        db.update("UPDATE complaints SET status='Cancelled' WHERE id=? AND user_id=?",complaint_id,uid(s));
        flash(s,"Complaint #"+complaint_id+" has been cancelled.");
        return redirect("/complaints/track");
    }

    @GetMapping("/complaints/success/{id}")
    @ResponseBody public ResponseEntity<?> complaintSuccess(@PathVariable Integer id,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Map<String,Object>> r=db.queryForList("SELECT * FROM complaints WHERE id=? AND user_id=?",id,uid(s));
        if(r.isEmpty()) return redirect("/complaints");
        return html("student/complaint_success.html",s,Map.of("complaint",r.get(0)));
    }

    @GetMapping("/admin/complaints")
    @ResponseBody public ResponseEntity<?> adminComplaints(HttpSession s){
        if(!logged(s)||!role(s,"admin","complaint_admin")) return denied();
        List<Map<String,Object>> rows=db.queryForList("SELECT c.*,u.name student_name,u.email student_email FROM complaints c LEFT JOIN users u ON u.id=c.user_id ORDER BY c.created_at DESC,c.id DESC");
        return html("admin/complaints.html",s,Map.of("complaints",rows,"role",s.getAttribute("role"),"name",s.getAttribute("name")));
    }

    @PostMapping("/admin/complaints/status")
    @ResponseBody public ResponseEntity<?> updateComplaintStatus(@RequestParam Integer complaint_id,@RequestParam String status,HttpSession s){
        if(!logged(s)||!role(s,"admin","complaint_admin")) return denied();
        if(!Set.of("Submitted","Problem Noticed","In Progress","Problem Addressed","Closed","Cancelled").contains(status)){
            flash(s,"Invalid complaint status update.");return redirect("/admin/complaints");
        }
        db.update("UPDATE complaints SET status=? WHERE id=?",status,complaint_id);
        flash(s,"Complaint #"+complaint_id+" status updated to "+status+".");
        return redirect("/admin/complaints");
    }

    @GetMapping("/store")
    @ResponseBody public ResponseEntity<?> store(@RequestParam(defaultValue="") String q,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Map<String,Object>> products=q.isBlank()
                ? db.queryForList("SELECT * FROM products ORDER BY name")
                : db.queryForList("SELECT * FROM products WHERE name LIKE ? ORDER BY name","%"+q+"%");
        return html("student/store.html",s,Map.of("products",products,"search",q));
    }

    @GetMapping("/store/add/{id}")
    @ResponseBody public ResponseEntity<?> addToCart(@PathVariable Integer id,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Integer> cart=cart(s,"cart");
        List<Map<String,Object>> p=db.queryForList("SELECT * FROM products WHERE id=? AND stock>0",id);
        if(p.isEmpty()){flash(s,"That item is not available.");return redirect("/store");}
        db.update("UPDATE products SET stock=stock-1 WHERE id=? AND stock>0",id);
        cart.add(id); s.setAttribute("cart",cart); flash(s,p.get(0).get("name")+" added to your cart.");
        return redirect("/store");
    }

    @GetMapping("/store/cart")
    @ResponseBody public ResponseEntity<?> storeCart(HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Integer> storeCart=cart(s,"cart");
        Map<Integer,Integer> qty=counts(storeCart);
        List<Map<String,Object>> items=new ArrayList<>();
        double total=0;
        for(Integer id:qty.keySet()){
            List<Map<String,Object>> rows=db.queryForList("SELECT * FROM products WHERE id=?",id);
            if(rows.isEmpty()) continue;
            Map<String,Object> product=rows.get(0), item=new HashMap<>();
            int q=qty.get(id); double line=((Number)product.get("price")).doubleValue()*q;
            item.put("product",product); item.put("quantity",q); item.put("line_total",line);
            items.add(item); total+=line;
        }
        return html("student/cart.html",s,Map.of("items",items,"total",total,"cart_count",storeCart.size()));
    }

    @PostMapping("/store/cart/increase/{id}")
    @ResponseBody public ResponseEntity<?> increase(@PathVariable Integer id,HttpSession s){
        if(!logged(s)) return redirect("/login");
        int updated=db.update("UPDATE products SET stock=stock-1 WHERE id=? AND stock>0",id);
        if(updated==0){flash(s,"That item is not available.");return redirect("/store/cart");}
        List<Integer> c=cart(s,"cart"); c.add(id); s.setAttribute("cart",c); return redirect("/store/cart");
    }

    @PostMapping("/store/cart/decrease/{id}")
    @ResponseBody public ResponseEntity<?> decrease(@PathVariable Integer id,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Integer> c=cart(s,"cart");
        if(c.remove(id)) db.update("UPDATE products SET stock=stock+1 WHERE id=?",id);
        s.setAttribute("cart",c); return redirect("/store/cart");
    }

    @GetMapping("/store/cart/clear")
    @ResponseBody public ResponseEntity<?> clearStore(HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Integer> c=cart(s,"cart");
        for(Integer id:c) db.update("UPDATE products SET stock=stock+1 WHERE id=?",id);
        s.setAttribute("cart",new ArrayList<Integer>());
        flash(s,"College Store cart cleared.");
        return redirect("/store/cart");
    }

    @PostMapping("/canteen/cart/increase/{id}")
    @ResponseBody public ResponseEntity<?> increaseCanteen(@PathVariable Integer id,HttpSession s){
        if(!logged(s)) return redirect("/login");
        int updated=db.update("UPDATE canteen_items SET stock=stock-1,available=CASE WHEN stock-1>0 THEN 1 ELSE 0 END WHERE id=? AND stock>0",id);
        if(updated==0){flash(s,"That food item is not available.");return redirect("/canteen/cart");}
        List<Integer> c=cart(s,"canteen_cart"); c.add(id); s.setAttribute("canteen_cart",c); return redirect("/canteen/cart");
    }

    @PostMapping("/canteen/cart/decrease/{id}")
    @ResponseBody public ResponseEntity<?> decreaseCanteen(@PathVariable Integer id,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Integer> c=cart(s,"canteen_cart");
        if(c.remove(id)) db.update("UPDATE canteen_items SET stock=stock+1,available=1 WHERE id=?",id);
        s.setAttribute("canteen_cart",c); return redirect("/canteen/cart");
    }

    @PostMapping("/cart/place-order")
    @ResponseBody public ResponseEntity<?> placeUnifiedOrder(
            @RequestParam(required=false) String student_name,
            @RequestParam(required=false) String department,
            HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Integer> storeCart=cart(s,"cart");
        List<Integer> canteenCart=cart(s,"canteen_cart");
        if(storeCart.isEmpty() && canteenCart.isEmpty()){
            flash(s,"Your cart is empty.");
            return redirect("/store/cart");
        }
        String[] who=identity(s,student_name,department);
        Map<Integer,Integer> storeQty=counts(storeCart);
        Map<Integer,Integer> canteenQty=counts(canteenCart);
        List<Map<String,Object>> products=new ArrayList<>();
        List<Map<String,Object>> foods=new ArrayList<>();

        for(Integer id:storeQty.keySet()){
            List<Map<String,Object>> r=db.queryForList("SELECT * FROM products WHERE id=?",id);
            if(r.isEmpty()){flash(s,"One of the store items is no longer available.");return redirect("/store/cart");}
            products.add(r.get(0));
        }
        // Stock is reserved when items are added to the cart.
        for(Integer id:canteenQty.keySet()){
            List<Map<String,Object>> r=db.queryForList("SELECT * FROM canteen_items WHERE id=?",id);
            if(r.isEmpty()){flash(s,"One of the canteen items is no longer available.");return redirect("/store/cart");}
            foods.add(r.get(0));
        }
        // Canteen stock is reserved when items are added to the cart.

        Long storeOrderId=null, canteenOrderId=null;
        if(!products.isEmpty()){
            double total=0; List<String> texts=new ArrayList<>();
            for(Map<String,Object> p:products){int id=((Number)p.get("id")).intValue(),q=storeQty.get(id);double price=((Number)p.get("price")).doubleValue();total+=price*q;texts.add(p.get("name")+" x"+q);}
            String number=orderNumber("store_orders"); final double finalTotal=total;
            KeyHolder kh=new GeneratedKeyHolder();
            db.update(con->{PreparedStatement ps=con.prepareStatement("INSERT INTO store_orders(user_id,student_name,department,order_number,total_amount,items,status) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1,uid(s));ps.setString(2,who[0]);ps.setString(3,who[1]);ps.setString(4,number);ps.setDouble(5,finalTotal);ps.setString(6,String.join(", ",texts));ps.setString(7,"Received");return ps;},kh);
            storeOrderId=kh.getKey().longValue();
            // Store stock was already decremented when the items were added to the cart.
        }

        if(!foods.isEmpty()){
            String number=orderNumber("canteen_orders"); double total=0;
            for(Map<String,Object> item:foods) total+=((Number)item.get("price")).doubleValue()*canteenQty.get(((Number)item.get("id")).intValue());
            final double finalTotal=total; KeyHolder kh=new GeneratedKeyHolder();
            db.update(con->{PreparedStatement ps=con.prepareStatement("INSERT INTO canteen_orders(user_id,student_name,department,order_number,total_amount,status) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1,uid(s));ps.setString(2,who[0]);ps.setString(3,who[1]);ps.setString(4,number);ps.setDouble(5,finalTotal);ps.setString(6,"Received");return ps;},kh);
            canteenOrderId=kh.getKey().longValue();
            for(Map<String,Object> item:foods){int id=((Number)item.get("id")).intValue(),q=canteenQty.get(id);db.update("INSERT INTO canteen_order_items(order_id,item_id,quantity,price) VALUES(?,?,?,?)",canteenOrderId,id,q,item.get("price"));}
        }

        s.setAttribute("cart",new ArrayList<Integer>());
        s.setAttribute("canteen_cart",new ArrayList<Integer>());
        if(storeOrderId!=null && canteenOrderId!=null) return redirect("/payment/combined?store_id="+storeOrderId+"&canteen_id="+canteenOrderId);
        if(storeOrderId!=null) return redirect("/payment/store/"+storeOrderId);
        return redirect("/payment/canteen/"+canteenOrderId);
    }

    @PostMapping("/store/place-order")
    @ResponseBody public ResponseEntity<?> placeStoreOrder(
            @RequestParam(required=false) String student_name,@RequestParam(required=false) String department,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Integer> cart=cart(s,"cart"); if(cart.isEmpty()){flash(s,"Your cart is empty.");return redirect("/store/cart");}
        Map<Integer,Integer> qty=counts(cart); List<Map<String,Object>> products=new ArrayList<>();
        for(Integer id:qty.keySet()){
            List<Map<String,Object>> r=db.queryForList("SELECT * FROM products WHERE id=?",id);
            if(!r.isEmpty()) products.add(r.get(0));
        }
        // Stock is already reserved when the items were added to the cart.
        String[] who=identity(s,student_name,department); double total=0; List<String> texts=new ArrayList<>();
        for(Map<String,Object> p:products){int id=((Number)p.get("id")).intValue(),q=qty.get(id);double price=((Number)p.get("price")).doubleValue();total+=price*q;texts.add(p.get("name")+" x"+q);}
        String number=orderNumber("store_orders");
        final double finalTotal = total;
        KeyHolder kh=new GeneratedKeyHolder();
        db.update(con->{PreparedStatement ps=con.prepareStatement("INSERT INTO store_orders(user_id,student_name,department,order_number,total_amount,items,status) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1,uid(s));ps.setString(2,who[0]);ps.setString(3,who[1]);ps.setString(4,number);ps.setDouble(5,finalTotal);ps.setString(6,String.join(", ",texts));ps.setString(7,"Received");return ps;},kh);
        s.setAttribute("cart",new ArrayList<Integer>());
        return redirect("/payment/store/"+kh.getKey().longValue());
    }

    @GetMapping("/store/order-success/{number}")
    @ResponseBody public ResponseEntity<?> storeSuccess(@PathVariable String number,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Map<String,Object>> r=db.queryForList("SELECT * FROM store_orders WHERE order_number=? AND user_id=?",number,uid(s));
        if(r.isEmpty()) return redirect("/store/cart");
        return html("student/store_order_success.html",s,Map.of("order",r.get(0)));
    }

    @GetMapping("/canteen")
    @ResponseBody public ResponseEntity<?> canteen(@RequestParam(defaultValue="") String q,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Map<String,Object>> items=q.isBlank()
                ? db.queryForList("SELECT * FROM canteen_items WHERE available=1 AND stock>0 ORDER BY category,name")
                : db.queryForList("SELECT * FROM canteen_items WHERE available=1 AND stock>0 AND (name LIKE ? OR category LIKE ?) ORDER BY category,name","%"+q+"%","%"+q+"%");
        return html("student/canteen.html",s,Map.of("items",items,"cart_count",cart(s,"canteen_cart").size(),"search",q));
    }

    @GetMapping("/canteen/add/{id}")
    @ResponseBody public ResponseEntity<?> addCanteen(@PathVariable Integer id,HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Map<String,Object>> r=db.queryForList("SELECT * FROM canteen_items WHERE id=? AND available=1 AND stock>0",id);
        if(r.isEmpty()){flash(s,"That food item is not available.");return redirect("/canteen");}
        int updated=db.update("UPDATE canteen_items SET stock=stock-1,available=CASE WHEN stock-1>0 THEN 1 ELSE 0 END WHERE id=? AND stock>0",id);
        if(updated==0){flash(s,"That food item is not available.");return redirect("/canteen");}
        List<Integer> c=cart(s,"canteen_cart");c.add(id);s.setAttribute("canteen_cart",c);flash(s,r.get(0).get("name")+" added to your canteen cart.");return redirect("/canteen");
    }

    @GetMapping("/canteen/cart")
    @ResponseBody public ResponseEntity<?> canteenCart(HttpSession s){
        if(!logged(s)) return redirect("/login");
        List<Integer> canteenCart=cart(s,"canteen_cart");
        Map<Integer,Integer> qty=counts(canteenCart);
        List<Map<String,Object>> items=new ArrayList<>();
        double total=0;
        for(Integer id:qty.keySet()){
            List<Map<String,Object>> rows=db.queryForList("SELECT * FROM canteen_items WHERE id=?",id);
            if(rows.isEmpty()) continue;
            Map<String,Object> food=rows.get(0), item=new HashMap<>();
            int q=qty.get(id); double line=((Number)food.get("price")).doubleValue()*q;
            item.put("item",food); item.put("quantity",q); item.put("line_total",line);
            items.add(item); total+=line;
        }
        return html("student/canteen_cart.html",s,Map.of("items",items,"total",total,"cart_count",canteenCart.size()));
    }

    @GetMapping("/canteen/cart/clear")
    @ResponseBody public ResponseEntity<?> clearCanteen(HttpSession s){
        if(!logged(s))return redirect("/login");
        List<Integer> c=cart(s,"canteen_cart");
        for(Integer id:c) db.update("UPDATE canteen_items SET stock=stock+1,available=1 WHERE id=?",id);
        s.setAttribute("canteen_cart",new ArrayList<Integer>());flash(s,"Canteen items cleared from your cart.");return redirect("/canteen/cart");
    }

    @PostMapping("/canteen/place-order")
    @ResponseBody public ResponseEntity<?> placeCanteen(@RequestParam(required=false) String student_name,@RequestParam(required=false) String department,HttpSession s){
        if(!logged(s))return redirect("/login");
        List<Integer> cart=cart(s,"canteen_cart");if(cart.isEmpty()){flash(s,"Your canteen cart is empty.");return redirect("/canteen/cart");}
        Map<Integer,Integer> qty=counts(cart);List<Map<String,Object>> items=new ArrayList<>();
        for(Integer id:qty.keySet()){List<Map<String,Object>> r=db.queryForList("SELECT * FROM canteen_items WHERE id=?",id);if(!r.isEmpty())items.add(r.get(0));}
        String[] who=identity(s,student_name,department);String number=orderNumber("canteen_orders");
        double total=0;
        for(Map<String,Object> i:items) total+=((Number)i.get("price")).doubleValue()*qty.get(((Number)i.get("id")).intValue());
        final double finalTotal = total;
        KeyHolder kh=new GeneratedKeyHolder();
        db.update(con->{PreparedStatement ps=con.prepareStatement("INSERT INTO canteen_orders(user_id,student_name,department,order_number,total_amount,status) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1,uid(s));ps.setString(2,who[0]);ps.setString(3,who[1]);ps.setString(4,number);ps.setDouble(5,finalTotal);ps.setString(6,"Received");return ps;},kh);
        long oid=kh.getKey().longValue();
        for(Map<String,Object> i:items){int id=((Number)i.get("id")).intValue(),n=qty.get(id);db.update("INSERT INTO canteen_order_items(order_id,item_id,quantity,price) VALUES(?,?,?,?)",oid,id,n,i.get("price"));}
        s.setAttribute("canteen_cart",new ArrayList<Integer>());return redirect("/payment/canteen/"+oid);
    }

    @GetMapping("/canteen/order-success/{number}")
    @ResponseBody public ResponseEntity<?> canteenSuccess(@PathVariable String number,HttpSession s){
        if(!logged(s))return redirect("/login");List<Map<String,Object>>r=db.queryForList("SELECT * FROM canteen_orders WHERE order_number=? AND user_id=?",number,uid(s));if(r.isEmpty())return redirect("/canteen/orders");return html("student/canteen_order_success.html",s,Map.of("order",r.get(0)));
    }

    @GetMapping("/canteen/orders")
    @ResponseBody public ResponseEntity<?> canteenOrders(HttpSession s){if(!logged(s))return redirect("/login");return html("student/canteen_orders.html",s,Map.of("orders",db.queryForList("SELECT * FROM canteen_orders WHERE user_id=? ORDER BY created_at DESC,id DESC",uid(s))));}

    @GetMapping("/track-orders")
    @ResponseBody public ResponseEntity<?> trackOrders(HttpSession s){
        if(!logged(s))return redirect("/login");List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> r:db.queryForList("SELECT student_name,department,total_amount,status,items,created_at FROM store_orders WHERE user_id=?",uid(s)))out.add(track("College Store",r.get("student_name"),r.get("department"),r.get("items"),r.get("total_amount"),r.get("status"),r.get("created_at")));
        for(Map<String,Object> r:db.queryForList("SELECT co.student_name,co.department,co.total_amount,co.status,co.created_at,COALESCE(GROUP_CONCAT(ci.name || ' x' || coi.quantity, ', '), 'Canteen food order') AS items FROM canteen_orders co LEFT JOIN canteen_order_items coi ON coi.order_id=co.id LEFT JOIN canteen_items ci ON ci.id=coi.item_id WHERE co.user_id=? GROUP BY co.id ORDER BY co.created_at DESC,co.id DESC",uid(s)))out.add(track("Canteen",r.get("student_name"),r.get("department"),r.get("items"),r.get("total_amount"),r.get("status"),r.get("created_at")));
        for(Map<String,Object> r:db.queryForList("SELECT student_name,department,filename,copies,page_count,color,sides,total_amount,status,created_at FROM print_orders WHERE user_id=?",uid(s))){
            String d=r.get("filename")+" · "+Objects.toString(r.get("page_count"),"1")+" page(s) · "+r.get("copies")+" copy/copies · "+r.get("color")+" · "+r.get("sides");
            out.add(track("Printing",r.get("student_name"),r.get("department"),d,r.get("total_amount"),r.get("status"),r.get("created_at")));
        }
        out.sort(Comparator.comparing((Map<String,Object> x) -> Objects.toString(x.get("created_at"), "")).reversed());
        return html("student/track_orders.html",s,Map.of("orders",out));
    }

    private Map<String,Object> track(String type,Object name,Object dept,Object details,Object amount,Object status,Object date){
        Map<String,Object> m=new HashMap<>();m.put("type",type);m.put("student_name",name);m.put("department",dept);m.put("details",details);m.put("amount",amount);m.put("status",status);m.put("created_at",date);return m;
    }

    @RequestMapping(value="/printing",method=RequestMethod.GET)
    @ResponseBody public ResponseEntity<?> printingGet(HttpSession s){if(!logged(s))return redirect("/login");return html("student/printing.html",s,Map.of());}

    @PostMapping("/printing")
    @ResponseBody public ResponseEntity<?> printingPost(@RequestParam MultipartFile file,@RequestParam(defaultValue="1") int copies,
            @RequestParam(defaultValue="1") int page_count,@RequestParam(defaultValue="B&W") String color,@RequestParam(defaultValue="Single") String sides,
            @RequestParam(required=false) String student_name,@RequestParam(required=false) String department,HttpSession s){
        if(!logged(s))return redirect("/login");
        try{
            String filename=safe(file.getOriginalFilename());Path target=printingDir.resolve(filename);file.transferTo(target);
            int pages=Math.max(1,page_count);
            if(filename.toLowerCase().endsWith(".pdf"))try(PDDocument doc=Loader.loadPDF(target.toFile())){pages=Math.max(1,doc.getNumberOfPages());}
            int cp=Math.max(1,copies);double rate="Colour".equals(color)?5.0:2.0;double total=pages*cp*rate;final double finalTotal=total;final int finalPages=pages;String[]who=identity(s,student_name,department);
            KeyHolder kh=new GeneratedKeyHolder();db.update(con->{PreparedStatement ps=con.prepareStatement("INSERT INTO print_orders(user_id,student_name,department,filename,copies,page_count,color,sides,total_amount,status) VALUES(?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1,uid(s));ps.setString(2,who[0]);ps.setString(3,who[1]);ps.setString(4,filename);ps.setInt(5,cp);ps.setInt(6,finalPages);ps.setString(7,color);ps.setString(8,sides);ps.setDouble(9,finalTotal);ps.setString(10,"Received");return ps;},kh);
            return redirect("/payment/printing/"+kh.getKey().longValue());
        }catch(Exception e){flash(s,"Could not process the printing file.");return redirect("/printing");}
    }

    @GetMapping("/printing/order-success/{id}")
    @ResponseBody public ResponseEntity<?> printSuccess(@PathVariable Integer id,HttpSession s){if(!logged(s))return redirect("/login");List<Map<String,Object>>r=db.queryForList("SELECT * FROM print_orders WHERE id=? AND user_id=?",id,uid(s));if(r.isEmpty())return redirect("/printing");return html("student/print_order_success.html",s,Map.of("order",r.get(0)));}

    @RequestMapping(value="/admin/store/items",method={RequestMethod.GET,RequestMethod.POST})
    @ResponseBody public ResponseEntity<?> adminStoreItems(@RequestParam(required=false) Integer product_id,@RequestParam(defaultValue="") String q,HttpSession s){
        if(!logged(s)||!role(s,"admin","store_admin"))return denied();
        if(product_id!=null){List<Map<String,Object>>p=db.queryForList("SELECT * FROM products WHERE id=?",product_id);if(!p.isEmpty()){db.update("DELETE FROM products WHERE id=?",product_id);flash(s,"Removed "+p.get(0).get("name")+" from the store.");}return redirect("/admin/store/items");}
        List<Map<String,Object>>p=q.isBlank()?db.queryForList("SELECT * FROM products ORDER BY name"):db.queryForList("SELECT * FROM products WHERE name LIKE ? ORDER BY name","%"+q+"%");
        return html("admin/store_items.html",s,Map.of("products",p,"search",q,"role",s.getAttribute("role"),"name",s.getAttribute("name")));
    }

    @PostMapping("/admin/store/items/update-stock")
    @ResponseBody public ResponseEntity<?> updateStoreStock(@RequestParam Integer product_id,@RequestParam Integer stock,HttpSession s){if(!logged(s)||!role(s,"admin","store_admin"))return denied();db.update("UPDATE products SET stock=? WHERE id=?",Math.max(0,stock),product_id);flash(s,"Store stock updated.");return redirect("/admin/store/items");}

    @PostMapping("/admin/store/items/add")
    @ResponseBody public ResponseEntity<?> addStoreItem(@RequestParam String name,@RequestParam double price,@RequestParam(defaultValue="0") int stock,HttpSession s){if(!logged(s)||!role(s,"admin","store_admin"))return denied();db.update("INSERT INTO products(name,price,stock) VALUES(?,?,?)",name.trim(),price,Math.max(0,stock));flash(s,"Store item added successfully.");return redirect("/admin/store/items");}

    @RequestMapping(value="/admin/canteen/items",method={RequestMethod.GET,RequestMethod.POST})
    @ResponseBody public ResponseEntity<?> adminCanteenItems(@RequestParam(required=false) String name,@RequestParam(required=false) String category,@RequestParam(required=false) Double price,
            @RequestParam(defaultValue="50") int stock,@RequestParam(defaultValue="") String q,HttpSession s){
        if(!logged(s)||!role(s,"admin","canteen_admin"))return denied();
        if(name!=null&&category!=null&&price!=null){int st=Math.max(0,stock);db.update("INSERT INTO canteen_items(name,category,price,available,stock) VALUES(?,?,?,?,?)",name.trim(),category.trim(),price,st>0?1:0,st);flash(s,"Food item added successfully.");return redirect("/admin/canteen/items");}
        List<Map<String,Object>>items=q.isBlank()?db.queryForList("SELECT * FROM canteen_items ORDER BY category,name"):db.queryForList("SELECT * FROM canteen_items WHERE name LIKE ? OR category LIKE ? ORDER BY category,name","%"+q+"%","%"+q+"%");
        return html("admin/canteen_items.html",s,Map.of("items",items,"search",q,"role",s.getAttribute("role"),"name",s.getAttribute("name")));
    }

    @PostMapping("/admin/canteen/items/update-stock")
    @ResponseBody public ResponseEntity<?> updateCanteenStock(@RequestParam Integer item_id,@RequestParam Integer stock,HttpSession s){if(!logged(s)||!role(s,"admin","canteen_admin"))return denied();int st=Math.max(0,stock);db.update("UPDATE canteen_items SET stock=?,available=? WHERE id=?",st,st>0?1:0,item_id);flash(s,"Canteen stock updated.");return redirect("/admin/canteen/items");}

    @PostMapping("/admin/canteen/items/delete")
    @ResponseBody public ResponseEntity<?> deleteCanteenItem(@RequestParam Integer item_id,HttpSession s){if(!logged(s)||!role(s,"admin","canteen_admin"))return denied();db.update("DELETE FROM canteen_items WHERE id=?",item_id);flash(s,"Food item deleted.");return redirect("/admin/canteen/items");}

    @GetMapping("/payment/combined")
    @ResponseBody public ResponseEntity<?> combinedPayment(@RequestParam(required=false) Integer store_id,
                                                            @RequestParam(required=false) Integer canteen_id,
                                                            HttpSession s){
        if(!logged(s)) return redirect("/login");
        Map<String,Object> model=new HashMap<>(); double total=0;
        if(store_id!=null){
            List<Map<String,Object>> r=db.queryForList("SELECT * FROM store_orders WHERE id=? AND user_id=?",store_id,uid(s));
            if(r.isEmpty()) return ResponseEntity.status(404).body("Store order not found");
            model.put("store_order",r.get(0)); total+=((Number)r.get(0).get("total_amount")).doubleValue();
        }
        if(canteen_id!=null){
            List<Map<String,Object>> r=db.queryForList("SELECT * FROM canteen_orders WHERE id=? AND user_id=?",canteen_id,uid(s));
            if(r.isEmpty()) return ResponseEntity.status(404).body("Canteen order not found");
            model.put("canteen_order",r.get(0)); total+=((Number)r.get(0).get("total_amount")).doubleValue();
        }
        model.put("store_id",store_id); model.put("canteen_id",canteen_id); model.put("total",total);
        return html("student/combined_payment.html",s,model);
    }

    @PostMapping("/payment/combined/upload")
    @ResponseBody public ResponseEntity<?> uploadCombinedPayment(@RequestParam(required=false) Integer store_id,
                                                                  @RequestParam(required=false) Integer canteen_id,
                                                                  @RequestParam MultipartFile payment_screenshot,
                                                                  HttpSession s){
        if(!logged(s)) return redirect("/login");
        String filename="combined_"+uid(s)+"_"+System.currentTimeMillis()+"_"+safe(payment_screenshot.getOriginalFilename());
        try{
            payment_screenshot.transferTo(paymentDir.resolve(filename));
            if(store_id!=null) db.update("UPDATE store_orders SET payment_screenshot=?,payment_status='Submitted' WHERE id=? AND user_id=?",filename,store_id,uid(s));
            if(canteen_id!=null) db.update("UPDATE canteen_orders SET payment_screenshot=?,payment_status='Submitted' WHERE id=? AND user_id=?",filename,canteen_id,uid(s));
            flash(s,"Payment screenshot submitted for admin verification.");
            return html("student/combined_payment_success.html",s,Map.of("filename",filename));
        }catch(Exception e){flash(s,"Could not upload the payment screenshot.");return redirect("/payment/combined?store_id="+Objects.toString(store_id,"")+"&canteen_id="+Objects.toString(canteen_id,""));}
    }

    @GetMapping("/payment/{service}/{id}")
    @ResponseBody public ResponseEntity<?> payment(@PathVariable String service,@PathVariable Integer id,HttpSession s){
        if(!logged(s)||!Set.of("store","canteen","printing").contains(service))return denied();
        String table=table(service);List<Map<String,Object>>r=db.queryForList("SELECT * FROM "+table+" WHERE id=? AND user_id=?",id,uid(s));if(r.isEmpty())return ResponseEntity.status(404).body("Order not found");
        return html("student/payment.html",s,Map.of("service",service,"order",r.get(0)));
    }

    @PostMapping("/payment/{service}/{id}/upload")
    @ResponseBody public ResponseEntity<?> uploadPayment(@PathVariable String service,@PathVariable Integer id,@RequestParam MultipartFile payment_screenshot,HttpSession s){
        if(!logged(s))return redirect("/login");if(!Set.of("store","canteen","printing").contains(service))return ResponseEntity.status(404).body("Invalid payment service");
        String filename=service+"_"+id+"_"+uid(s)+"_"+safe(payment_screenshot.getOriginalFilename());
        try{payment_screenshot.transferTo(paymentDir.resolve(filename));db.update("UPDATE "+table(service)+" SET payment_screenshot=?,payment_status='Submitted' WHERE id=? AND user_id=?",filename,id,uid(s));flash(s,"Payment screenshot submitted for admin verification.");List<Map<String,Object>>r=db.queryForList("SELECT * FROM "+table(service)+" WHERE id=? AND user_id=?",id,uid(s));return html("student/payment_success.html",s,Map.of("service",service,"order",r.get(0)));}catch(Exception e){flash(s,"Could not upload the payment screenshot.");return redirect("/payment/"+service+"/"+id);}
    }

    private String table(String service){return Map.of("store","store_orders","canteen","canteen_orders","printing","print_orders").get(service);}

    @GetMapping("/student/complaints/file/{id}") @ResponseBody public ResponseEntity<?> studentComplaintFile(@PathVariable Integer id,HttpSession s){if(!logged(s)||!"student".equals(s.getAttribute("role")))return denied();List<Map<String,Object>>r=db.queryForList("SELECT image FROM complaints WHERE id=? AND user_id=?",id,uid(s));return r.isEmpty()?ResponseEntity.notFound().build():file(r.get(0).get("image"),complaintDir,false);}
    @GetMapping("/student/printing/file/{id}") @ResponseBody public ResponseEntity<?> studentPrintingFile(@PathVariable Integer id,HttpSession s){if(!logged(s)||!"student".equals(s.getAttribute("role")))return denied();List<Map<String,Object>>r=db.queryForList("SELECT filename FROM print_orders WHERE id=? AND user_id=?",id,uid(s));return r.isEmpty()?ResponseEntity.notFound().build():file(r.get(0).get("filename"),printingDir,false);}
    @GetMapping("/student/payment/{service}/{id}/file") @ResponseBody public ResponseEntity<?> studentPaymentFile(@PathVariable String service,@PathVariable Integer id,HttpSession s){if(!logged(s)||!"student".equals(s.getAttribute("role")))return denied();List<Map<String,Object>>r=db.queryForList("SELECT payment_screenshot FROM "+table(service)+" WHERE id=? AND user_id=?",id,uid(s));return r.isEmpty()?ResponseEntity.notFound().build():file(r.get(0).get("payment_screenshot"),paymentDir,false);}

    @GetMapping("/admin/complaints/file/{filename:.+}") @ResponseBody public ResponseEntity<?> complaintFile(@PathVariable String filename,HttpSession s){if(!logged(s)||!role(s,"admin","complaint_admin"))return denied();return file(filename,complaintDir,false);}
    @GetMapping("/admin/printing/file/{filename:.+}") @ResponseBody public ResponseEntity<?> printingFile(@PathVariable String filename,HttpSession s){if(!logged(s)||!role(s,"admin","printing_admin"))return denied();return file(filename,printingDir,true);}
    @GetMapping("/uploads/payments/{filename:.+}") @ResponseBody public ResponseEntity<?> paymentFile(@PathVariable String filename){return file(filename,paymentDir,false);}

    private ResponseEntity<?> file(Object name,Path dir,boolean download){
        if(name==null||String.valueOf(name).isBlank())return ResponseEntity.notFound().build();
        Path p=dir.resolve(safe(String.valueOf(name))).normalize();
        if(!p.startsWith(dir.normalize())||!Files.exists(p))return ResponseEntity.notFound().build();
        try{
            InputStreamResource r=new InputStreamResource(Files.newInputStream(p));
            MediaType mt=MediaType.APPLICATION_OCTET_STREAM;
            try{String ct=Files.probeContentType(p);if(ct!=null)mt=MediaType.parseMediaType(ct);}catch(Exception ignored){}
            String disp=(download?"attachment":"inline")+"; filename=\""+p.getFileName()+"\"";
            return ResponseEntity.ok().contentType(mt).header(HttpHeaders.CONTENT_DISPOSITION,disp).body(r);
        }catch(IOException e){return ResponseEntity.status(500).body("File error");}
    }

    @PostMapping("/admin/payment/{service}/{id}/verify")
    @ResponseBody public ResponseEntity<?> verifyPayment(@PathVariable String service,@PathVariable Integer id,HttpSession s){if(!logged(s)||!role(s,"admin","store_admin","canteen_admin","printing_admin"))return denied();if(table(service)==null)return ResponseEntity.status(404).body("Invalid service");db.update("UPDATE "+table(service)+" SET payment_status='Verified' WHERE id=?",id);flash(s,"Payment marked as verified.");return redirect("/admin/dashboard");}

    @GetMapping("/admin/store/orders") @ResponseBody public ResponseEntity<?> adminStoreOrders(HttpSession s){if(!logged(s)||!role(s,"admin","store_admin"))return denied();return html("admin/store_orders.html",s,Map.of("orders",db.queryForList("SELECT so.*,COALESCE(so.student_name,u.name) customer_name,COALESCE(so.department,'') customer_department FROM store_orders so LEFT JOIN users u ON u.id=so.user_id WHERE COALESCE(so.is_archived,0)=0 ORDER BY so.created_at DESC,so.id DESC"),"role",s.getAttribute("role"),"name",s.getAttribute("name")));}
    @PostMapping("/admin/store/orders/update-status") @ResponseBody public ResponseEntity<?> updateStoreStatus(@RequestParam Integer order_id,@RequestParam String status,HttpSession s){if(!logged(s)||!role(s,"admin","store_admin"))return denied();if(!Set.of("Processing","Order Received","Order Ready").contains(status)){flash(s,"Please select a valid store order status.");return redirect("/admin/store/orders");}db.update("UPDATE store_orders SET status=? WHERE id=?",status,order_id);flash(s,"Store order status updated successfully.");return redirect("/admin/store/orders");}

    @GetMapping("/admin/canteen/orders") @ResponseBody public ResponseEntity<?> adminCanteenOrders(HttpSession s){if(!logged(s)||!role(s,"admin","canteen_admin"))return denied();return html("admin/canteen_orders.html",s,Map.of("orders",db.queryForList("SELECT co.*,COALESCE(co.student_name,u.name) customer_name,COALESCE(co.department,'') customer_department,COALESCE(GROUP_CONCAT(ci.name || ' x' || coi.quantity, ', '), 'Canteen food order') items FROM canteen_orders co LEFT JOIN users u ON u.id=co.user_id LEFT JOIN canteen_order_items coi ON coi.order_id=co.id LEFT JOIN canteen_items ci ON ci.id=coi.item_id WHERE COALESCE(co.is_archived,0)=0 GROUP BY co.id ORDER BY co.created_at DESC,co.id DESC"),"role",s.getAttribute("role"),"name",s.getAttribute("name")));}
    @PostMapping("/admin/canteen/orders/update-status") @ResponseBody public ResponseEntity<?> updateCanteenStatus(@RequestParam Integer order_id,@RequestParam String status,HttpSession s){if(!logged(s)||!role(s,"admin","canteen_admin"))return denied();if(!Set.of("Processing","Order Received","Order Ready").contains(status)){flash(s,"Please select a valid canteen order status.");return redirect("/admin/canteen/orders");}db.update("UPDATE canteen_orders SET status=? WHERE id=?",status,order_id);flash(s,"Canteen order status updated successfully.");return redirect("/admin/canteen/orders");}

    @PostMapping("/admin/printing/orders/update-status") @ResponseBody public ResponseEntity<?> updatePrintingStatus(@RequestParam Integer order_id,@RequestParam String status,HttpSession s){if(!logged(s)||!role(s,"admin","printing_admin"))return denied();if(!Set.of("Received","Processing","Printing","Ready","Completed").contains(status)){flash(s,"Please select a valid printing order status.");return redirect("/admin/dashboard");}db.update("UPDATE print_orders SET status=? WHERE id=?",status,order_id);flash(s,"Printing order status updated successfully.");return redirect("/admin/dashboard");}

    @RequestMapping(value="/admin/store/orders/archive",method={RequestMethod.GET,RequestMethod.POST}) @ResponseBody public ResponseEntity<?> archiveStore(@RequestParam(required=false) Integer order_id,HttpSession s){return archive("store_orders",order_id,"/admin/store/orders","Store order moved to history.",s);}
    @RequestMapping(value="/admin/canteen/orders/archive",method={RequestMethod.GET,RequestMethod.POST}) @ResponseBody public ResponseEntity<?> archiveCanteen(@RequestParam(required=false) Integer order_id,HttpSession s){return archive("canteen_orders",order_id,"/admin/canteen/orders","Canteen order moved to history.",s);}
    @RequestMapping(value="/admin/printing/orders/archive",method={RequestMethod.GET,RequestMethod.POST}) @ResponseBody public ResponseEntity<?> archivePrinting(@RequestParam(required=false) Integer order_id,HttpSession s){return archive("print_orders",order_id,"/admin/dashboard","Printing order moved to history.",s);}
    private ResponseEntity<?> archive(String table,Integer id,String back,String msg,HttpSession s){if(!logged(s)||!role(s,"admin","store_admin","printing_admin","canteen_admin","complaint_admin"))return denied();if(id==null){flash(s,"Invalid order ID.");return redirect(back);}db.update("UPDATE "+table+" SET is_archived=1 WHERE id=?",id);flash(s,msg);return redirect(back);}

    @RequestMapping(value="/admin/store/orders/restore",method={RequestMethod.GET,RequestMethod.POST}) @ResponseBody public ResponseEntity<?> restoreStore(@RequestParam(required=false) Integer order_id,HttpSession s){return restore("store_orders",order_id,"/admin/store/orders/history","Store order restored to active orders.",s);}
    @RequestMapping(value="/admin/canteen/orders/restore",method={RequestMethod.GET,RequestMethod.POST}) @ResponseBody public ResponseEntity<?> restoreCanteen(@RequestParam(required=false) Integer order_id,HttpSession s){return restore("canteen_orders",order_id,"/admin/canteen/orders/history","Canteen order restored to active orders.",s);}
    @RequestMapping(value="/admin/printing/orders/restore",method={RequestMethod.GET,RequestMethod.POST}) @ResponseBody public ResponseEntity<?> restorePrinting(@RequestParam(required=false) Integer order_id,HttpSession s){return restore("print_orders",order_id,"/admin/printing/orders/history","Printing order restored to active orders.",s);}
    private ResponseEntity<?> restore(String table,Integer id,String back,String msg,HttpSession s){if(!logged(s)||!role(s,"admin","store_admin","printing_admin","canteen_admin","complaint_admin"))return denied();if(id==null){flash(s,"Invalid order ID.");return redirect(back);}db.update("UPDATE "+table+" SET is_archived=0 WHERE id=?",id);flash(s,msg);return redirect(back);}

    @GetMapping("/admin/store/orders/history") @ResponseBody public ResponseEntity<?> storeHistory(HttpSession s){if(!logged(s)||!role(s,"admin","store_admin","printing_admin","canteen_admin","complaint_admin"))return denied();return html("admin/store_order_history.html",s,Map.of("orders",db.queryForList("SELECT so.*,COALESCE(so.student_name,u.name) customer_name,COALESCE(so.department,'') customer_department FROM store_orders so JOIN users u ON u.id=so.user_id WHERE COALESCE(so.is_archived,0)=1 ORDER BY so.created_at DESC,so.id DESC")));}

    @GetMapping("/admin/canteen/orders/history") @ResponseBody public ResponseEntity<?> canteenHistory(HttpSession s){if(!logged(s)||!role(s,"admin","canteen_admin","store_admin","printing_admin","complaint_admin"))return denied();return html("admin/canteen_order_history.html",s,Map.of("orders",db.queryForList("SELECT co.*,COALESCE(co.student_name,u.name) customer_name,COALESCE(co.department,'') customer_department FROM canteen_orders co JOIN users u ON u.id=co.user_id WHERE COALESCE(co.is_archived,0)=1 ORDER BY co.created_at DESC,co.id DESC")));}

    @GetMapping("/admin/printing/orders/history") @ResponseBody public ResponseEntity<?> printingHistory(HttpSession s){if(!logged(s)||!role(s,"admin","printing_admin","store_admin","canteen_admin","complaint_admin"))return denied();return html("admin/printing_order_history.html",s,Map.of("orders",db.queryForList("SELECT po.*,COALESCE(po.student_name,u.name) customer_name,COALESCE(po.department,'') customer_department FROM print_orders po JOIN users u ON u.id=po.user_id WHERE COALESCE(po.is_archived,0)=1 ORDER BY po.created_at DESC,po.id DESC")));}

    @GetMapping("/admin/dashboard") @ResponseBody public ResponseEntity<?> adminDashboard(HttpSession s){
        if(!logged(s)||!role(s,"admin","canteen_admin","complaint_admin","store_admin","printing_admin"))return denied();
        Map<String,Object> stats=new HashMap<>();stats.put("users",count("users"));stats.put("complaints",count("complaints"));stats.put("products",count("products"));stats.put("food",count("canteen_items"));stats.put("prints",count("print_orders"));stats.put("store_orders",count("store_orders"));stats.put("canteen_orders",count("canteen_orders"));
        String r=Objects.toString(s.getAttribute("role"),"");Map<String,Object> m=new HashMap<>(stats);
        if(r.equals("admin")||r.equals("store_admin")){m.put("store_items",db.queryForList("SELECT * FROM products ORDER BY name"));m.put("orders",db.queryForList("SELECT so.*,COALESCE(so.student_name,u.name) customer_name,COALESCE(so.department,'') customer_department FROM store_orders so JOIN users u ON u.id=so.user_id ORDER BY so.created_at DESC,so.id DESC"));}
        if(r.equals("admin")||r.equals("canteen_admin")){m.put("canteen_items",db.queryForList("SELECT * FROM canteen_items ORDER BY category,name"));m.put("canteen_orders",db.queryForList("SELECT co.*,COALESCE(co.student_name,u.name) customer_name,COALESCE(co.department,'') customer_department FROM canteen_orders co JOIN users u ON u.id=co.user_id ORDER BY co.created_at DESC,co.id DESC"));}
        if(r.equals("admin")||r.equals("complaint_admin"))m.put("complaints",db.queryForList("SELECT c.*,u.name student_name,u.email student_email FROM complaints c LEFT JOIN users u ON u.id=c.user_id ORDER BY c.created_at DESC,c.id DESC"));
        if(r.equals("admin")||r.equals("printing_admin"))m.put("print_orders",db.queryForList("SELECT po.*,COALESCE(po.student_name,u.name) customer_name,COALESCE(po.department,'') customer_department FROM print_orders po JOIN users u ON u.id=po.user_id WHERE COALESCE(po.is_archived,0)=0 ORDER BY po.created_at DESC,po.id DESC"));
        m.put("role",r);m.put("name",s.getAttribute("name"));return html("admin/dashboard.html",s,m);
    }

    @GetMapping("/staff/dashboard") @ResponseBody public ResponseEntity<?> staffDashboard(HttpSession s){if(!logged(s)||!"staff".equals(s.getAttribute("role")))return denied();return html("staff/dashboard.html",s,Map.of("name",s.getAttribute("name")));}

    @GetMapping("/admin/complaints/file/{filename}/download")
    @ResponseBody public ResponseEntity<?> downloadComplaintFile(@PathVariable String filename,HttpSession s){if(!logged(s)||!role(s,"admin","complaint_admin"))return denied();return file(filename,complaintDir,true);}

    private long count(String table){Long n=db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);return n==null?0:n;}

    @SuppressWarnings("unchecked")
    private List<Integer> cart(HttpSession s,String key){
        Object o=s.getAttribute(key);return o instanceof List ? new ArrayList<>((List<Integer>)o):new ArrayList<>();
    }
    private Map<Integer,Integer> counts(List<Integer> list){Map<Integer,Integer>m=new LinkedHashMap<>();for(Integer x:list)m.put(x,m.getOrDefault(x,0)+1);return m;}
}
