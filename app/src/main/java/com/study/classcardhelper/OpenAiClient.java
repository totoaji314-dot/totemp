package com.study.classcardhelper;
import org.json.JSONArray; import org.json.JSONObject; import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets;
public final class OpenAiClient {
 public static final class Answer { public final String text; public final int confidence; public Answer(String t,int c){text=t==null?"":t.trim();confidence=c;} }
 private OpenAiClient(){}
 public static Answer solve(String apiKey,String model,String screenText) throws Exception {
  URL url=new URL("https://api.openai.com/v1/responses"); HttpURLConnection conn=(HttpURLConnection)url.openConnection(); conn.setRequestMethod("POST"); conn.setConnectTimeout(12000); conn.setReadTimeout(20000); conn.setDoOutput(true); conn.setRequestProperty("Authorization","Bearer "+apiKey); conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
  String prompt="You are an English study helper. Choose the best answer from the visible practice question. Return exactly two lines: ANSWER: <exact visible answer text or NONE> and CONFIDENCE: <0-100>. If login, payment, password, verification, or non-study screen, return NONE. SCREEN TEXT:\n"+screenText;
  JSONObject body=new JSONObject(); body.put("model",model==null||model.trim().isEmpty()?"gpt-5.6":model.trim()); body.put("input",prompt); body.put("max_output_tokens",80);
  try(OutputStream os=conn.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}
  int code=conn.getResponseCode(); String response=readAll(code>=200&&code<300?conn.getInputStream():conn.getErrorStream()); if(code<200||code>=300) throw new IllegalStateException("OpenAI HTTP "+code+": "+response);
  JSONObject root=new JSONObject(response); String out=extract(root); String answer=""; int conf=0; for(String line:out.split("\\r?\\n")){String t=line.trim(); if(t.regionMatches(true,0,"ANSWER:",0,7)) answer=t.substring(7).trim(); if(t.regionMatches(true,0,"CONFIDENCE:",0,11)) try{conf=Integer.parseInt(t.substring(11).replaceAll("[^0-9]",""));}catch(Exception ignored){}}
  if("NONE".equalsIgnoreCase(answer)) answer=""; return new Answer(answer,Math.max(0,Math.min(100,conf)));
 }
 private static String extract(JSONObject root){ if(root.has("output_text")) return root.optString("output_text",""); JSONArray output=root.optJSONArray("output"); if(output==null)return ""; StringBuilder sb=new StringBuilder(); for(int i=0;i<output.length();i++){JSONObject item=output.optJSONObject(i); if(item==null)continue; JSONArray content=item.optJSONArray("content"); if(content==null)continue; for(int j=0;j<content.length();j++){JSONObject c=content.optJSONObject(j); if(c!=null&&"output_text".equals(c.optString("type"))){if(sb.length()>0)sb.append('\n'); sb.append(c.optString("text",""));}}} return sb.toString(); }
 private static String readAll(InputStream in)throws Exception{ if(in==null)return ""; StringBuilder sb=new StringBuilder(); try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line; while((line=br.readLine())!=null)sb.append(line).append('\n');} return sb.toString(); }
}
