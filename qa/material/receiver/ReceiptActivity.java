package org.mathstudy.qa.delivery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.ViewGroup;
import android.widget.*;
import android.webkit.WebView;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.json.JSONObject;

/** Independent QA APK: receives with its own UID, copies, hashes and renders the received bytes.
 * It verifies delivery and basic structure; it is not a production Office compatibility oracle. */
public class ReceiptActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this); column.setOrientation(1);
        ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
        try {
            Intent intent = getIntent(); boolean send = Intent.ACTION_SEND.equals(intent.getAction());
            Uri uri = send ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
            if (uri == null) throw new IOException("Missing stream");
            String mime = getContentResolver().getType(uri);
            File file = new File(getFilesDir(), send ? "received.bin" : "opened.bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
            try (InputStream input = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) >= 0) { size += n; if (size > 30L*1024*1024) throw new IOException("Too large"); digest.update(buffer,0,n); out.write(buffer,0,n); }
            }
            StringBuilder hash = new StringBuilder(); for(byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
            TextView title = new TextView(this); title.setText((send ? "已实际接收" : "已实际打开") + "\n" + mime + "\nSHA256 " + hash); column.addView(title);
            String rendered;
            if ("application/pdf".equals(mime)) {
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer pdf = new PdfRenderer(fd)) {
                    rendered = "PDF pages=" + pdf.getPageCount();
                    for(int i=0; i<pdf.getPageCount(); i++) try(PdfRenderer.Page page=pdf.openPage(i)) {
                        Bitmap bitmap=Bitmap.createBitmap(714,1010,Bitmap.Config.ARGB_8888); bitmap.eraseColor(-1);
                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        ImageView image=new ImageView(this); image.setImageBitmap(bitmap); image.setAdjustViewBounds(true); column.addView(image);
                    }
                }
            } else {
                rendered = mime != null && mime.contains("openxmlformats") ? office(file, mime) : "<pre>" + escape(new String(read(new FileInputStream(file)), "UTF-8")) + "</pre>";
                WebView web = new WebView(this); web.getSettings().setJavaScriptEnabled(false); web.getSettings().setAllowFileAccess(false); web.getSettings().setBlockNetworkLoads(true);
                web.loadDataWithBaseURL(null,"<meta charset='utf-8'><style>body{font:18px sans-serif}table{border-collapse:collapse}td{border:1px solid #aaa;padding:6px}img{max-width:95%}pre{white-space:pre-wrap}</style>"+rendered,"text/html","UTF-8",null);
                column.addView(web,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1800));
            }
            JSONObject receipt=new JSONObject(); receipt.put("action",intent.getAction()); receipt.put("mime",mime); receipt.put("sha256",hash.toString()); receipt.put("size",size); receipt.put("rendered",rendered);
            try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(getFilesDir(),send ? "send.json" : "view.json")),"UTF-8")) { out.write(receipt.toString()); }
        } catch(Exception error) { TextView text=new TextView(this); text.setText("FAILED " + error); column.addView(text); }
    }
    static byte[] read(InputStream input) throws IOException { try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) { byte[] b=new byte[8192]; int n; while((n=in.read(b))>=0) { if(out.size()+n>30*1024*1024)throw new IOException("Too large"); out.write(b,0,n); } return out.toByteArray(); } }
    static String escape(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
    static Element xml(ZipFile zip,String path) throws Exception {
        String text=new String(read(zip.getInputStream(zip.getEntry(path))),"UTF-8"); if(text.contains("<!DOCTYPE") || text.contains("<!ENTITY"))throw new IOException("Entities forbidden");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); return f.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes("UTF-8"))).getDocumentElement();
    }
    static String content(Node n) {
        String name=n.getLocalName(); if("t".equals(name) || "v".equals(name))return escape(n.getTextContent());
        if("f".equals(name) && n.getNamespaceURI()!=null && n.getNamespaceURI().endsWith("/math")) { String num="",den=""; for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling()) { if("num".equals(c.getLocalName()))num=content(c); if("den".equals(c.getLocalName()))den=content(c); } return "("+num+")/("+den+")"; }
        if("sSup".equals(name)) { String base="",power=""; for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling()) { if("e".equals(c.getLocalName()))base=content(c); if("sup".equals(c.getLocalName()))power=content(c); } return base+"<sup>"+power+"</sup>"; }
        StringBuilder out=new StringBuilder(); for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())out.append(content(c));
        if("tbl".equals(name))return "<table>"+out+"</table>"; if("tr".equals(name))return "<tr>"+out+"</tr>"; if("tc".equals(name))return "<td>"+out+"</td>"; if("p".equals(name))return "<p>"+out+"</p>";
        return out.toString();
    }
    static String office(File file,String mime) throws Exception {
        try(ZipFile zip=new ZipFile(file)) {
            StringBuilder out=new StringBuilder();
            if(mime.contains("wordprocessing")) {
                out.append(content(xml(zip,"word/document.xml")));
                Enumeration<? extends ZipEntry> entries=zip.entries(); while(entries.hasMoreElements()) { ZipEntry e=entries.nextElement(); if(e.getName().startsWith("word/media/") && e.getName().endsWith(".png"))out.append("<img src='data:image/png;base64,").append(android.util.Base64.encodeToString(read(zip.getInputStream(e)),android.util.Base64.NO_WRAP)).append("'>"); }
            } else if(mime.contains("spreadsheet")) {
                Element workbook=xml(zip,"xl/workbook.xml"); NodeList sheets=workbook.getElementsByTagNameNS("*","sheet");
                for(int i=0;i<sheets.getLength();i++)out.append("<h2>").append(escape(((Element)sheets.item(i)).getAttribute("name"))).append("</h2>");
                Element sheet=xml(zip,"xl/worksheets/sheet1.xml"); NodeList rows=sheet.getElementsByTagNameNS("*","row"); out.append("<table>");
                for(int i=0;i<rows.getLength();i++) { out.append("<tr>"); NodeList cells=((Element)rows.item(i)).getElementsByTagNameNS("*","c"); for(int j=0;j<cells.getLength();j++) { Element c=(Element)cells.item(j); out.append("<td>").append(content(c)).append(" <small>[").append(escape(c.getAttribute("t").isEmpty()?"number":c.getAttribute("t"))).append("]</small></td>"); } out.append("</tr>"); } out.append("</table>");
            } else {
                for(int i=1;zip.getEntry("ppt/slides/slide"+i+".xml")!=null;i++)out.append("<h2>Slide ").append(i).append("</h2>").append(content(xml(zip,"ppt/slides/slide"+i+".xml")));
            }
            return out.toString();
        }
    }
}
