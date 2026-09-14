package com.example.visitororder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "visitor_order_prefs"
private const val STOCKS = "stocks"
private const val ORDERS = "orders"

data class CartLine(val product: Product, val qty: Int)
data class OrderLine(val code: String, val name: String, val qty: Int, val price: Long)
data class OrderRecord(val id: String, val customer: String, val total: Long, val finalAmount: Long, val cash: Boolean, val volumeDiscount: Int, val cashDiscount: Int, val lines: List<OrderLine>, val time: String)

private fun money(n: Long): String = DecimalFormat("#,###").format(n) + " ریال"
private fun discounts(total: Long): Pair<Int, Int> = when {
    total >= 5_000_000_000L -> 10 to 5
    total >= 2_000_000_000L -> 9 to 5
    total >= 500_000_000L -> 8 to 4
    total >= 200_000_000L -> 7 to 3
    total >= 100_000_000L -> 6 to 3
    total >= 50_000_000L -> 5 to 3
    total >= 10_000_000L -> 4 to 2
    else -> 0 to 0
}

class OrderViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var products by mutableStateOf(ProductCatalog.products)
        private set
    var cart by mutableStateOf(emptyList<CartLine>())
        private set
    var orders by mutableStateOf(loadOrders())
        private set

    private fun stockMap(): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        val json = prefs.getString(STOCKS, null)
        if (json != null) runCatching { JSONObject(json).keys().forEach { k -> out[k] = JSONObject(json).optInt(k) } }
        if (out.isEmpty()) products.forEach { out[it.code] = it.initialStock }
        return out
    }
    private var stocks = stockMap()
    init { refreshProducts() }
    private fun refreshProducts() { products = ProductCatalog.products.map { it.copy(initialStock = stocks[it.code] ?: it.initialStock) } }
    private fun saveStocks() { prefs.edit().putString(STOCKS, JSONObject(stocks as Map<*, *>).toString()).apply() }

    fun stock(p: Product) = stocks[p.code] ?: p.initialStock
    fun add(p: Product, qty: Int) {
        if (qty < 1) return
        val current = cart.firstOrNull { it.product.code == p.code }?.qty ?: 0
        if (current + qty <= stock(p)) {
            cart = if (current == 0) cart + CartLine(p, qty) else cart.map { if (it.product.code == p.code) it.copy(qty = it.qty + qty) else it }
        }
    }
    fun setQty(p: Product, qty: Int) { if (qty <= 0) remove(p) else if (qty <= stock(p)) cart = cart.map { if (it.product.code == p.code) it.copy(qty = qty) else it } }
    fun remove(p: Product) { cart = cart.filterNot { it.product.code == p.code } }
    fun total() = cart.sumOf { it.product.price * it.qty }

    fun register(customer: String, cash: Boolean): OrderRecord? {
        if (cart.isEmpty()) return null
        val total = total(); val (v, c) = discounts(total)
        val final = if (cash) total - total * (v + c) / 100 else total - total * v / 100
        cart.forEach { stocks[it.product.code] = stock(it.product) - it.qty }
        saveStocks(); refreshProducts()
        val record = OrderRecord(System.currentTimeMillis().toString(), customer.ifBlank { "بدون نام" }, total, final, cash, v, c,
            cart.map { OrderLine(it.product.code, it.product.name, it.qty, it.product.price) }, SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date()))
        orders = listOf(record) + orders; saveOrders(); cart = emptyList(); return record
    }
    fun cancel(order: OrderRecord) {
        order.lines.forEach { stocks[it.code] = (stocks[it.code] ?: 0) + it.qty }
        saveStocks(); refreshProducts(); orders = orders.filterNot { it.id == order.id }; saveOrders()
    }
    private fun saveOrders() {
        val a = JSONArray(); orders.forEach { o -> val j=JSONObject(); j.put("id",o.id).put("customer",o.customer).put("total",o.total).put("final",o.finalAmount).put("cash",o.cash).put("v",o.volumeDiscount).put("c",o.cashDiscount).put("time",o.time); val l=JSONArray(); o.lines.forEach { x -> l.put(JSONObject().put("code",x.code).put("name",x.name).put("qty",x.qty).put("price",x.price)) }; j.put("lines",l); a.put(j) }; prefs.edit().putString(ORDERS,a.toString()).apply()
    }
    private fun loadOrders(): List<OrderRecord> = runCatching {
        val a=JSONArray(prefs.getString(ORDERS,"[]")); buildList { for(i in 0 until a.length()){ val j=a.getJSONObject(i); val l=j.getJSONArray("lines"); val lines=buildList { for(k in 0 until l.length()){ val x=l.getJSONObject(k); add(OrderLine(x.getString("code"),x.getString("name"),x.getInt("qty"),x.getLong("price"))) } }; add(OrderRecord(j.getString("id"),j.getString("customer"),j.getLong("total"),j.getLong("final"),j.getBoolean("cash"),j.getInt("v"),j.getInt("c"),lines,j.getString("time"))) } }
    }.getOrDefault(emptyList())
}

@Composable
fun App(vm: OrderViewModel = viewModel()) {
    var screen by remember { mutableStateOf("home") }
    var query by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf("") }
    var cash by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = lightColorScheme(primary = MaterialTheme.colorScheme.primary)) {
            when(screen) {
                "home" -> HomeScreen(vm, { screen="products" }, { screen="cart" }, { screen="orders" })
                "products" -> ProductsScreen(vm, query, { query=it }, { screen="cart" }, { screen="home" })
                "cart" -> CartScreen(vm, customer, {customer=it}, cash, {cash=it}, { screen="home" }) { order ->
                    val text = buildOrderText(order); ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,text) }, "ارسال سفارش")); screen="home"
                }
                "orders" -> OrdersScreen(vm, { screen="home" }, { order ->
                    val text=buildOrderText(order); ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,text) }, "ارسال سفارش"))
                })
            }
        }
    }
}

@Composable fun HomeScreen(vm: OrderViewModel, products:()->Unit, cart:()->Unit, orders:()->Unit) {
    Scaffold(topBar={TopAppBar(title={Text("سفارش‌گیر ویزیتور", fontWeight=FontWeight.Bold)})}) { p -> Column(Modifier.padding(p).padding(20.dp).fillMaxSize(), verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("ثبت سفارش آفلاین", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
        Text("کالاها، موجودی و سفارش‌ها روی همین گوشی ذخیره می‌شوند.")
        Button(products, Modifier.fillMaxWidth().height(56.dp)){ Text("کالاها و جست‌وجو") }
        OutlinedButton(cart, Modifier.fillMaxWidth().height(56.dp)){ Text("سبد سفارش  •  ${vm.cart.sumOf { it.qty }} قلم") }
        OutlinedButton(orders, Modifier.fillMaxWidth().height(56.dp)){ Text("سفارش‌های ثبت‌شده  •  ${vm.orders.size}") }
        Spacer(Modifier.weight(1f)); Text("نسخه ۱.۰  •  کاملاً آفلاین", style=MaterialTheme.typography.bodySmall)
    }}
}

@Composable fun ProductsScreen(vm: OrderViewModel, query:String, setQuery:(String)->Unit, cart:()->Unit, back:()->Unit) {
    val filtered=vm.products.filter { query.isBlank() || it.name.contains(query,true) || it.code.contains(query,true) }
    Scaffold(topBar={TopAppBar(title={Text("انتخاب کالا")}, navigationIcon={TextButton(back){Text("بازگشت")}}, actions={TextButton(cart){Text("سبد")}})}) { p -> Column(Modifier.padding(p).padding(horizontal=12.dp)) {
        OutlinedTextField(query,setQuery,Modifier.fillMaxWidth().padding(vertical=8.dp),singleLine=true,label={Text("جست‌وجوی نام یا کد کالا")})
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) { items(filtered, key={it.code}) { pr -> ProductCard(vm,pr) } }
    }}
}
@Composable fun ProductCard(vm:OrderViewModel,p:Product){ var qty by remember { mutableStateOf(1) }; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
    Text(p.name,fontWeight=FontWeight.Bold); Text("کد: ${p.code}  •  ${p.unit}"); Text("قیمت: ${money(p.price)}"); Text("موجودی: ${vm.stock(p)}")
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){ OutlinedTextField(qty.toString(),{it.toIntOrNull()?.let{v->qty=v.coerceAtLeast(1)}},Modifier.width(90.dp),singleLine=true,label={Text("تعداد")}); Button({vm.add(p,qty)}){Text("افزودن")}}
} } }

@Composable fun CartScreen(vm:OrderViewModel, customer:String,setCustomer:(String)->Unit,cash:Boolean,setCash:(Boolean)->Unit,home:()->Unit,registered:(OrderRecord)->Unit){ val total=vm.total(); val (v,c)=discounts(total); val final=if(cash) total-total*(v+c)/100 else total-total*v/100
 Scaffold(topBar={TopAppBar(title={Text("سبد سفارش")},navigationIcon={TextButton(home){Text("بازگشت")}})}){p->Column(Modifier.padding(p).padding(12.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
    OutlinedTextField(customer,setCustomer,Modifier.fillMaxWidth(),label={Text("نام مشتری (اختیاری)")},singleLine=true)
    vm.cart.forEach { line -> Card(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(line.product.name,fontWeight=FontWeight.Bold);Text("${line.qty} × ${money(line.product.price)}")}; TextButton({vm.remove(line.product)}){Text("حذف")}}} }
    if(vm.cart.isNotEmpty()){
      Text("مبلغ اصلی: ${money(total)}",fontWeight=FontWeight.Bold); Text("تخفیف حجمی: $v٪  •  تخفیف نقدی: ${if(cash)c else 0}%")
      Row(verticalAlignment=Alignment.CenterVertically){Text("پرداخت نقدی");Switch(cash,setCash)}
      Text("مبلغ نهایی: ${money(final)}",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
      Button({vm.register(customer,cash)?.let(registered)},Modifier.fillMaxWidth().height(54.dp)){Text("ثبت نهایی سفارش")}
    } else Text("سبد خالی است")
 }} }
}

@Composable fun OrdersScreen(vm:OrderViewModel,back:()->Unit,share:(OrderRecord)->Unit){ Scaffold(topBar={TopAppBar(title={Text("تاریخچه سفارش‌ها")},navigationIcon={TextButton(back){Text("بازگشت")}})}){p->LazyColumn(Modifier.padding(p).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){items(vm.orders,key={it.id}){o->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(o.customer,fontWeight=FontWeight.Bold);Text(o.time);Text("مبلغ نهایی: ${money(o.finalAmount)}");Text("${o.lines.sumOf{it.qty}} قلم  •  ${if(o.cash)"نقدی" else "غیرنقدی"}");Row{TextButton({share(o)}){Text("ارسال")};TextButton({vm.cancel(o)}){Text("لغو و برگشت موجودی")}}}}}}}}

private fun buildOrderText(o:OrderRecord):String=buildString{append("سفارش فروش\n");append("مشتری: ${o.customer}\nتاریخ: ${o.time}\n");o.lines.forEachIndexed{i,l->append("${i+1}. ${l.name} | ${l.code} | ${l.qty} × ${money(l.price)}\n")};append("مبلغ اصلی: ${money(o.total)}\nتخفیف حجمی: ${o.volumeDiscount}%\nتخفیف نقدی: ${if(o.cash)o.cashDiscount else 0}%\nمبلغ نهایی: ${money(o.finalAmount)}\n")}

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{App()}}}
