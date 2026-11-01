package com.example.contractorstatements

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NAVY = 0xFF1A365D.toInt()
private const val GREEN = 0xFF0CA678.toInt()
private fun dec(s:String?):BigDecimal = try { BigDecimal((s?:"").trim().replace('٫','.').replace(',','.').ifEmpty{"0"}) } catch (_:Exception) { BigDecimal.ZERO }
private fun BigDecimal.fmt()=setScale(2,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
private data class Work(var title:String="",var unit:String="م3",var total:String="0",var previous:String="0",var current:String="0",var rate:String="0") {
 fun cumulative()=dec(previous)+dec(current)
 fun amount()=dec(current)*dec(rate)
 fun allAmount()=cumulative()*dec(rate)
}
private class Store(a:Activity):SQLiteOpenHelper(a,"statements.db",null,1) {
 override fun onCreate(d:SQLiteDatabase){
  d.execSQL("CREATE TABLE heads(id INTEGER PRIMARY KEY AUTOINCREMENT,project TEXT,contractor TEXT,num TEXT,dt TEXT,period TEXT,tax TEXT,retention TEXT,advance TEXT,penalty TEXT)")
  d.execSQL("CREATE TABLE lines(id INTEGER PRIMARY KEY AUTOINCREMENT,head_id INTEGER,title TEXT,unit TEXT,total TEXT,previous TEXT,current TEXT,rate TEXT)")
 }
 override fun onUpgrade(d:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
 fun save(id:Long?,head:List<String>,lines:List<Work>):Long {
  val d=writableDatabase;d.beginTransaction()
  try {
   val v=ContentValues()
   listOf("project","contractor","num","dt","period","tax","retention","advance","penalty").forEachIndexed{i,key->v.put(key,head[i])}
   val key=if(id==null)d.insertOrThrow("heads",null,v) else {d.update("heads",v,"id=?",arrayOf(id.toString()));d.delete("lines","head_id=?",arrayOf(id.toString()));id}
   lines.forEach{w->
    val x=ContentValues();x.put("head_id",key);x.put("title",w.title);x.put("unit",w.unit);x.put("total",w.total);x.put("previous",w.previous);x.put("current",w.current);x.put("rate",w.rate)
    d.insertOrThrow("lines",null,x)
   }
   d.setTransactionSuccessful();return key
  }finally{d.endTransaction()}
 }
 fun previous(project:String,contractor:String,title:String,unit:String,exclude:Long?):BigDecimal{
  val args=mutableListOf(project,contractor,title,unit)
  val extra=if(exclude!=null){args.add(exclude.toString());" AND h.id<>?"}else ""
  var amount=BigDecimal.ZERO
  readableDatabase.rawQuery("SELECT l.current FROM lines l JOIN heads h ON h.id=l.head_id WHERE h.project=? AND h.contractor=? AND l.title=? AND l.unit=?"+extra,args.toTypedArray()).use{c->
   while(c.moveToNext())amount+=dec(c.getString(0))
  }
  return amount
 }
 fun history():List<Pair<Long,String>>{
  val result=mutableListOf<Pair<Long,String>>()
  readableDatabase.rawQuery("SELECT id,project,contractor,num FROM heads ORDER BY id DESC",null).use{c->
   while(c.moveToNext())result.add(c.getLong(0) to "${c.getString(1)} — ${c.getString(2)} — مستخلص ${c.getString(3)}")
  }
  return result
 }
 fun load(id:Long):Pair<List<String>,List<Work>>?{
  val h=readableDatabase.rawQuery("SELECT project,contractor,num,dt,period,tax,retention,advance,penalty FROM heads WHERE id=?",arrayOf(id.toString())).use{c->
   if(!c.moveToFirst())return null
   (0..8).map{c.getString(it)?:""}
  }
  val rows=mutableListOf<Work>()
  readableDatabase.rawQuery("SELECT title,unit,total,previous,current,rate FROM lines WHERE head_id=? ORDER BY id",arrayOf(id.toString())).use{c->
   while(c.moveToNext())rows.add(Work(c.getString(0)?:"",c.getString(1)?:"",c.getString(2)?:"0",c.getString(3)?:"0",c.getString(4)?:"0",c.getString(5)?:"0"))
  }
  return h to rows
 }
}

class MainActivity:Activity(){
 private lateinit var store:Store
 private lateinit var list:LinearLayout
 private lateinit var footer:TextView
 private lateinit var project:EditText
 private lateinit var contractor:EditText
 private lateinit var num:EditText
 private lateinit var dt:EditText
 private lateinit var period:EditText
 private lateinit var tax:EditText
 private lateinit var retention:EditText
 private lateinit var advance:EditText
 private lateinit var penalty:EditText
 private var selected:Long?=null
 private val rows=mutableListOf<Line>()
 private var busy=false
 private fun params()=LinearLayout.LayoutParams(-1,-2)
 private fun label(s:String,size:Float=16f):TextView=TextView(this).apply{text=s;textSize=size;setTextColor(NAVY);gravity=Gravity.RIGHT;setPadding(12,12,12,12)}
 private fun field(hintText:String,value:String="",numeric:Boolean=false):EditText=EditText(this).apply{
  hint=hintText;setText(value);textSize=15f;setSingleLine(true);textDirection=View.TEXT_DIRECTION_RTL
  if(numeric)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
  addTextChangedListener(object:TextWatcher{
   override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
   override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!busy)update()}
   override fun afterTextChanged(s:Editable?){}
  })
 }
 private fun button(s:String,fn:()->Unit)=Button(this).apply{text=s;isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(GREEN);setOnClickListener{fn()}}
 private fun card()=LinearLayout(this).apply{
  orientation=LinearLayout.VERTICAL;setPadding(16,12,16,12);setBackgroundColor(Color.WHITE);elevation=3f
  layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(8,8,8,8)}
 }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  window.statusBarColor=NAVY;window.navigationBarColor=NAVY;window.decorView.layoutDirection=View.LAYOUT_DIRECTION_RTL
  store=Store(this)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFFF1F5F9.toInt())}
  setContentView(root)
  root.addView(label("مستخلصات المقاولين",23f).apply{setTextColor(Color.WHITE);setBackgroundColor(NAVY)},params())
  val scroll=ScrollView(this);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(content)
  val head=card();head.addView(label("بيانات المستخلص",19f))
  project=field("اسم المشروع");contractor=field("اسم المقاول");num=field("رقم المستخلص","1")
  dt=field("تاريخ المستخلص",SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date()))
  period=field("فترة الإنجاز")
  listOf(project,contractor,num,dt,period).forEach{head.addView(it,params())};content.addView(head)
  content.addView(label("بنود الأعمال",19f))
  list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};content.addView(list)
  content.addView(button("+ إضافة بند"){addLine(Work())},params())
  val deductions=card();deductions.addView(label("الاستقطاعات",19f))
  tax=field("الضرائب %","0",true);retention=field("تأمين حسن التنفيذ %","0",true)
  advance=field("خصم الدفعة المقدمة","0",true);penalty=field("غرامات أخرى","0",true)
  listOf(tax,retention,advance,penalty).forEach{deductions.addView(it,params())};content.addView(deductions)
  val actions=card()
  actions.addView(button("حفظ المستخلص"){save()},params())
  actions.addView(button("مستخلص جديد وترحيل الكميات"){newStatement()},params())
  actions.addView(button("المستخلصات المحفوظة"){history()},params())
  actions.addView(button("تصدير PDF"){export("application/pdf","pdf",201)},params())
  actions.addView(button("تصدير Excel CSV"){export("text/csv","csv",202)},params())
  content.addView(actions)
  footer=label("الصافي المستحق للمقاول: 0",19f).apply{gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundColor(NAVY)}
  root.addView(footer,params());addLine(Work())
 }
 private inner class Line(w:Work){
  val panel=card()
  val title=field("البيان",w.title)
  val unit=field("الوحدة",w.unit)
  val total=field("الكمية الإجمالية",w.total,true)
  val previous=field("الكمية السابقة",w.previous,true)
  val current=field("الكمية الحالية",w.current,true)
  val rate=field("فئة السعر",w.rate,true)
  val info=label("",13f)
  fun value()=Work(title.text.toString(),unit.text.toString(),total.text.toString(),previous.text.toString(),current.text.toString(),rate.text.toString())
  init{
   listOf(title,unit,total,previous,current,rate).forEach{panel.addView(it,params())}
   panel.addView(info,params())
   panel.addView(button("ترحيل الكمية السابقة"){previous.setText(store.previous(project.text.toString().trim(),contractor.text.toString().trim(),title.text.toString().trim(),unit.text.toString().trim(),selected).fmt())},params())
   panel.addView(button("حذف البند"){rows.remove(this);list.removeView(panel);update()},params())
  }
 }
 private fun addLine(w:Work){val line=Line(w);rows.add(line);list.addView(line.panel);update()}
 private fun calc():Triple<BigDecimal,BigDecimal,BigDecimal>{
  val gross=rows.fold(BigDecimal.ZERO){a,r->a+r.value().amount()}
  val pct=(dec(tax.text.toString())+dec(retention.text.toString())).divide(BigDecimal(100),12,RoundingMode.HALF_UP)
  val deductions=gross*pct+dec(advance.text.toString())+dec(penalty.text.toString())
  return Triple(gross,deductions,gross-deductions)
 }
 private fun update(){
  if(!::footer.isInitialized||busy)return
  rows.forEach{r->val w=r.value();r.info.text="المنفذة: ${w.cumulative().fmt()} | الحالية: ${w.amount().fmt()} | حتى تاريخه: ${w.allAmount().fmt()}"}
  val(gross,minus,net)=calc()
  footer.text="قيمة الأعمال: ${gross.fmt()} | الاستقطاعات: ${minus.fmt()}\nالصافي المستحق للمقاول: ${net.fmt()}"
 }
 private fun values()=listOf(project,contractor,num,dt,period,tax,retention,advance,penalty).map{it.text.toString().trim()}
 private fun alert(s:String){Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
 private fun valid():Boolean{
  if(project.text.isBlank()||contractor.text.isBlank()||num.text.isBlank()){alert("أدخل المشروع والمقاول ورقم المستخلص");return false}
  if(rows.isEmpty()||rows.any{it.title.text.isBlank()||it.unit.text.isBlank()}){alert("أدخل بيان ووحدة كل بند");return false}
  if(rows.any{dec(it.current.text.toString())<BigDecimal.ZERO||dec(it.total.text.toString())<BigDecimal.ZERO||dec(it.rate.text.toString())<BigDecimal.ZERO||it.value().cumulative()>dec(it.total.text.toString())}){alert("راجع الكميات: القيم السالبة أو تجاوز كمية العقد غير مسموحة");return false}
  if(listOf(tax,retention).any{dec(it.text.toString())<BigDecimal.ZERO||dec(it.text.toString())>BigDecimal(100)}){alert("النسب يجب أن تكون بين صفر و100");return false}
  return true
 }
 private fun save(){
  if(!valid())return
  try{selected=store.save(selected,values(),rows.map{it.value()});alert("تم الحفظ")}catch(e:Exception){alert("فشل الحفظ: ${e.message}")}
 }
 private fun newStatement(){
  if(project.text.isBlank()||contractor.text.isBlank()){alert("أدخل اسم المشروع والمقاول");return}
  val p=project.text.toString().trim();val c=contractor.text.toString().trim();val items=rows.map{it.value()}
  busy=true;selected=null;num.setText((num.text.toString().toIntOrNull()?.plus(1)?:1).toString())
  dt.setText(SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date()))
  list.removeAllViews();rows.clear();busy=false
  items.forEach{w->w.previous=store.previous(p,c,w.title.trim(),w.unit.trim(),null).fmt();w.current="0";addLine(w)}
  alert("تم ترحيل الكميات السابقة")
 }
 private fun history(){
  val data=store.history();if(data.isEmpty()){alert("لا توجد مستخلصات محفوظة");return}
  AlertDialog.Builder(this).setTitle("المستخلصات").setItems(data.map{it.second}.toTypedArray()){_,index->
   val loaded=store.load(data[index].first)?:return@setItems
   busy=true;selected=data[index].first
   listOf(project,contractor,num,dt,period,tax,retention,advance,penalty).forEachIndexed{i,e->e.setText(loaded.first[i])}
   list.removeAllViews();rows.clear();busy=false
   loaded.second.forEach{addLine(it)};update()
  }.setNegativeButton("إغلاق",null).show()
 }
 private fun export(mime:String,ext:String,code:Int){
  if(!valid())return
  startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
   addCategory(Intent.CATEGORY_OPENABLE);type=mime;putExtra(Intent.EXTRA_TITLE,"مستخلص_${num.text}.$ext")
  },code)
 }
 @Deprecated("Activity result callback")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(resultCode!=RESULT_OK||data?.data==null)return
  try{
   contentResolver.openOutputStream(data.data!!)?.use{stream->stream.write(if(requestCode==201)pdf() else csv())}
   alert("تم التصدير بنجاح")
  }catch(e:Exception){alert("فشل التصدير: ${e.message}")}
 }
 private fun quote(s:String)="\""+s.replace("\"","\"\"")+"\""
 private fun csv():ByteArray{
  val lines=mutableListOf<String>()
  lines.add(listOf("المشروع",project.text.toString(),"المقاول",contractor.text.toString()).joinToString(","){quote(it)})
  lines.add(listOf("المستخلص",num.text.toString(),"التاريخ",dt.text.toString(),"الفترة",period.text.toString()).joinToString(","){quote(it)})
  lines.add(listOf("البيان","الوحدة","الإجمالية","السابقة","الحالية","المنفذة","السعر","القيمة الحالية","حتى تاريخه").joinToString(","){quote(it)})
  rows.forEach{r->val w=r.value();lines.add(listOf(w.title,w.unit,w.total,w.previous,w.current,w.cumulative().fmt(),w.rate,w.amount().fmt(),w.allAmount().fmt()).joinToString(","){quote(it)})}
  val(gross,minus,net)=calc()
  listOf("قيمة الأعمال الحالية" to gross.fmt(),"الضرائب %" to tax.text.toString(),"تأمين حسن التنفيذ %" to retention.text.toString(),"الدفعة المقدمة" to advance.text.toString(),"الغرامات" to penalty.text.toString(),"إجمالي الاستقطاعات" to minus.fmt(),"الصافي المستحق" to net.fmt()).forEach{lines.add(quote(it.first)+","+quote(it.second))}
  return ("\uFEFF"+lines.joinToString("\r\n")).toByteArray(Charsets.UTF_8)
 }
 private fun pdf():ByteArray{
  val document=PdfDocument();var pageNo=0;var page:PdfDocument.Page?=null;var y=0f
  val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=NAVY;textSize=12f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
  fun start(){
   page?.let{document.finishPage(it)}
   pageNo++;page=document.startPage(PdfDocument.PageInfo.Builder(595,842,pageNo).create())
   page!!.canvas.drawColor(Color.WHITE);y=35f
   paint.color=NAVY;paint.textSize=18f;paint.typeface=Typeface.DEFAULT_BOLD
   page!!.canvas.drawText("Contractor Statement",35f,y,paint);y+=35f
  }
  fun line(s:String){
   if(y>755f)start()
   paint.color=NAVY;paint.textSize=12f;paint.typeface=Typeface.DEFAULT
   val text=android.text.TextPaint(paint)
   val layout=android.text.StaticLayout.Builder.obtain(s,0,s.length,text,525).setAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE).setIncludePad(false).build()
   page!!.canvas.save();page!!.canvas.translate(35f,y);layout.draw(page!!.canvas);page!!.canvas.restore()
   y+=layout.height+10f
  }
  try{
   start()
   line("مستخلص المقاول رقم ${num.text} - المشروع: ${project.text}")
   line("المقاول: ${contractor.text} | التاريخ: ${dt.text} | الفترة: ${period.text}")
   rows.forEachIndexed{i,r->
    val w=r.value()
    line("${i+1}. ${w.title} [${w.unit}]")
    line("الإجمالية ${w.total} | السابقة ${w.previous} | الحالية ${w.current} | المنفذة ${w.cumulative().fmt()}")
    line("سعر الوحدة ${w.rate} | قيمة الحالية ${w.amount().fmt()} | حتى تاريخه ${w.allAmount().fmt()}")
   }
   val(gross,minus,net)=calc()
   line("إجمالي الأعمال الحالية: ${gross.fmt()}")
   line("الضرائب ${tax.text}% | التأمينات ${retention.text}% | الدفعة المقدمة ${advance.text} | الغرامات ${penalty.text}")
   line("إجمالي الاستقطاعات: ${minus.fmt()}")
   line("الصافي المستحق: ${net.fmt()}")
   if(y>670f)start()
   y+=20f;line("المهندس المشرف: ______________    مدير المشروع: ______________")
   line("المقاول: _____________________")
   page?.let{document.finishPage(it)}
   val out=ByteArrayOutputStream();document.writeTo(out);return out.toByteArray()
  }finally{document.close()}
 }
}
