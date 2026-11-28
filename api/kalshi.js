// Vercel Kalshi bridge. Every request requires a fresh HMAC signed with the\n// permanent owner's access code. Kalshi private credentials stay server-side.
const crypto = require("node:crypto");
const ORIGIN = "https://external-api.kalshi.com/trade-api/v2";
const SERIES = "KXBTC15M";
function reject(res, status, error) {
  res.status(status).json({error});
}
function authenticate(req) {
  // Permanent owner access key in Vercel. Never returned or embedded in the APK.
  // Each request must prove knowledge through a fresh HMAC-SHA256 signature.
  const key = process.env.KALSHI_OWNER_ACCESS_CODE;
  if (typeof key !== "string" || Buffer.byteLength(key, "utf8") < 20)
    throw Object.assign(new Error("OWNER_ACCESS_NOT_CONFIGURED"), {status:503});

  const ts=req.headers["x-trader-timestamp"];
  const signature=req.headers["x-trader-signature"];
  if (typeof ts!=="string" || !/^\d{13}$/.test(ts) ||
      Math.abs(Date.now()-Number(ts))>30_000 ||
      typeof signature!=="string" || !/^[A-Za-z0-9+/]{43}=$/.test(signature))
    throw Object.assign(new Error("AUTH_FAILED"),{status:401});

  const raw = typeof req.body==="string" ? req.body : JSON.stringify(req.body||{});
  const digest=crypto.createHash("sha256").update(raw, "utf8").digest("hex");
  const message=[ts,req.method.toUpperCase(),"/api/kalshi",digest].join("\n");
  const expected=crypto.createHmac("sha256",Buffer.from(key,"utf8"))
    .update(message,"utf8").digest();
  const supplied=Buffer.from(signature,"base64");
  if (supplied.length!==expected.length || !crypto.timingSafeEqual(expected,supplied))
    throw Object.assign(new Error("AUTH_FAILED"),{status:401});
}

function readKalshiPrivateKey(input) {
  if (typeof input!=="string") throw new Error("NOT_STRING");
  let value=input.trim();
  // Vercel values sometimes arrive as quoted JSON, literal "\n" escapes,
  // or base64 PKCS#8. Unwrap formatting without logging any secret bytes.
  for(let i=0;i<2;i++){
    if(value.startsWith('"') && value.endsWith('"')){
      const parsed=JSON.parse(value);
      if(typeof parsed!=="string")throw new Error("NOT_PEM_STRING");
      value=parsed.trim();
    }else if(value.startsWith("{") && value.endsWith("}")){
      const obj=JSON.parse(value);
      const v=obj.privateKey??obj.private_key??obj.pem;
      if(typeof v!=="string") throw new Error("NO_PRIVATE_KEY_IN_JSON");
      value=v.trim();
    }else break;
  }
  value=value.replace(/\\r\\n/g,"\n").replace(/\\n/g,"\n")
    .replace(/\\r/g,"\n").replace(/\r\n/g,"\n").replace(/\r/g,"\n").trim();
  const pemMatch=value.match(/-----BEGIN (RSA PRIVATE KEY|PRIVATE KEY|EC PRIVATE KEY)-----([\s\S]*?)-----END \1-----/);
  if(pemMatch){
    const label=pemMatch[1];
    const encoded=pemMatch[2].replace(/\s/g,"");
    if(!/^[A-Za-z0-9+/]+={0,2}$/.test(encoded))throw new Error("PEM_BASE64_INVALID");
    const lines=encoded.match(/.{1,64}/g).join("\n");
    const normalized="-----BEGIN "+label+"-----\n"+lines+"\n-----END "+label+"-----\n";
    return crypto.createPrivateKey(normalized);
  }
  // Some environment-variable helpers store the DER key as one base64 string.
  if(/^[A-Za-z0-9+/]{80,}={0,2}$/.test(value)){
    const der=Buffer.from(value,"base64");
    for(const type of ["pkcs8","pkcs1","sec1"]){
      try {return crypto.createPrivateKey({key:der,format:"der",type});}
      catch {/* Try another private-key encoding; never leak input. */}
    }
  }
  throw new Error("NOT_PRIVATE_PEM_OR_PKCS8");
}
function kalshiCredentials() {
  const id = process.env.KALSHI_API_ID;
  const pem = process.env.KALSHI_API_KEY;
  if (!id || !pem) throw Object.assign(new Error("KALSHI_NOT_CONFIGURED"),{status:503});
  let privateKey;
  try {
    privateKey=readKalshiPrivateKey(pem);
  } catch {
    throw Object.assign(new Error("KALSHI_PRIVATE_KEY_FORMAT_INVALID"),{status:503});
  }
  return {id,privateKey};
}
async function kalshi(method,path,body) {
  const {id,privateKey}=kalshiCredentials();
  const ts=Date.now().toString();
  const payload=Buffer.from(ts+method+"/trade-api/v2"+path.split("?")[0]);
  const signature=crypto.sign(privateKey.asymmetricKeyType==="ed25519"?null:"sha256",payload,{
    key:privateKey,
    ...(privateKey.asymmetricKeyType==="rsa" ? {
      padding:crypto.constants.RSA_PKCS1_PSS_PADDING,saltLength:32
    }:{})
  }).toString("base64");
  const response=await fetch(ORIGIN+path,{
    method,
    headers:{
      "KALSHI-ACCESS-KEY":id,
      "KALSHI-ACCESS-TIMESTAMP":ts,
      "KALSHI-ACCESS-SIGNATURE":signature,
      "content-type":"application/json"
    },
    body:body?JSON.stringify(body):undefined,
    signal:AbortSignal.timeout(6500)
  });
  if (!response.ok) {
    // Status number only. Never send Kalshi's response body or secrets to the phone.
    throw Object.assign(new Error("KALSHI_UPSTREAM_HTTP_"+response.status),{status:502});
  }
  return response.json();
}
function validateTicker(s) {
  return typeof s==="string" && /^KXBTC15M-[A-Za-z0-9-]+$/.test(s);
}
function quote(book,outcome) {
  const levels=book.orderbook_fp?.[outcome==="YES"?"no_dollars":"yes_dollars"];
  if (!Array.isArray(levels) || !levels.length) return null;
  const best=Math.max(...levels.map(x=>Number(x[0])).filter(x=>x>0&&x<1));
  return Number.isFinite(best) ? Math.round((1-best)*10000)/10000 : null;
}
function parseMarket(m) {
  return {ticker:m.ticker,close_time:m.close_time,status:m.status,result:m.result??null};
}
function validEntryWindow(closeTime) {
  const now=Date.now();
  const remaining=Math.floor((Date.parse(closeTime)-now)/60000);
  if (![12,10,9,8,7,5].includes(remaining)) return false;
  // Convert to Eastern time with daylight saving handled by Intl.
  const hour=Number(new Intl.DateTimeFormat("en-US",{
    timeZone:"America/New_York",hour:"2-digit",hourCycle:"h23"
  }).format(new Date(now)));
  return hour===10||hour===11||hour===17;
}
function clientOrderId(ticker) {
  const hex=crypto.createHash("sha256").update("kalshi-phone:"+ticker).digest("hex");
  return [hex.slice(0,8),hex.slice(8,12),"4"+hex.slice(13,16),
    "8"+hex.slice(17,20),hex.slice(20,32)].join("-");
}
async function funds(){
  const r=await kalshi("GET","/portfolio/balance");
  const cash=Number(r.balance);
  const holdings=Number(r.portfolio_value??0);
  if (!Number.isSafeInteger(cash)||!Number.isSafeInteger(holdings)||
      cash<0||holdings<0) throw Object.assign(new Error("KALSHI_BALANCE_RESPONSE_INVALID"),{status:503});
  return {equityCents:cash+holdings,cashCents:cash};
}
module.exports=async function handler(req,res){
  res.setHeader("Cache-Control","no-store");
  res.setHeader("X-Content-Type-Options","nosniff");
  res.setHeader("Access-Control-Allow-Origin","null");
  if(req.method!=="POST")return reject(res,405,"METHOD_NOT_ALLOWED");
  try{
    authenticate(req);
    const body = typeof req.body==="string" ? JSON.parse(req.body) : req.body;
    if(!body||typeof body!=="object")return reject(res,400,"INVALID_REQUEST");
    const action=body.action;
    if(action==="check_auth") return res.status(200).json({authenticated:true});
    if(action==="balance") return res.status(200).json(await funds());
    if(action==="current_market") {
      const r=await kalshi("GET","/markets?series_ticker=KXBTC15M&status=open&limit=100");
      const now=Date.now();
      const matches=(r.markets||[]).filter(m=>validateTicker(m.ticker) &&
        m.status==="open" && Number.isFinite(Date.parse(m.close_time)) &&
        Date.parse(m.close_time)-900000<=now && Date.parse(m.close_time)>now);
      return res.status(200).json({market:matches.length===1?parseMarket(matches[0]):null});
    }
    if(action==="fills"){
      if(typeof body.orderId!=="string"||!/^[-A-Za-z0-9]{8,128}$/.test(body.orderId))
        return reject(res,400,"INVALID_ORDER_ID");
      const all=[];let cursor="";
      for(let i=0;i<20;i++){
        const p="/portfolio/fills?order_id="+encodeURIComponent(body.orderId)+
          "&limit=1000"+(cursor?"&cursor="+encodeURIComponent(cursor):"");
        const r=await kalshi("GET",p);
        all.push(...(r.fills||[]).map(f=>({
          fill_id:f.fill_id,count_fp:f.count_fp,outcome_side:f.outcome_side,
          action:f.action,yes_price_dollars:f.yes_price_dollars,
          no_price_dollars:f.no_price_dollars,fee_cost:f.fee_cost
        })));
        cursor=r.cursor||"";
        if(!cursor)return res.status(200).json({fills:all});
      }
      return reject(res,503,"INCOMPLETE_FILLED_ORDERS");
    }
    if(!validateTicker(body.ticker))return reject(res,400,"INVALID_MARKET");
    if(action==="market"){
      const r=await kalshi("GET","/markets/"+body.ticker);
      return res.status(200).json({market:parseMarket(r.market)});
    }
    if(action==="ask") {
      if(!["YES","NO"].includes(body.outcome))return reject(res,400,"INVALID_OUTCOME");
      const r=await kalshi("GET","/markets/"+body.ticker+"/orderbook");
      return res.status(200).json({ask:quote(r,body.outcome)});
    }
    if(action==="order"){
      if(!["YES","NO"].includes(body.outcome)||!Number.isInteger(body.count)||
        body.count<1||body.count>1000)return reject(res,400,"INVALID_ORDER");
      const current=await kalshi("GET","/markets/"+body.ticker);
      const market=current.market;
      if(market?.status!=="open"||!validEntryWindow(market.close_time))
        return reject(res,409,"OUTSIDE_TRADE_WINDOW");
      const f=await funds();
      if(f.equityCents<10000)return reject(res,409,"BELOW_100_MINIMUM");
      // 60c max plus 10c/contract conservative fee allowance.
      if(body.count*70>Math.min(60000,Math.floor(f.equityCents*.30),f.cashCents))
        return reject(res,409,"BUDGET_EXCEEDED");
      const book=await kalshi("GET","/markets/"+body.ticker+"/orderbook");
      if(quote(book,body.outcome)!==.60)return reject(res,409,"PRICE_NOT_60");
      const order={
        ticker:body.ticker,
        client_order_id:clientOrderId(body.ticker),
        side:body.outcome==="YES"?"bid":"ask",
        count:String(body.count),
        price:body.outcome==="YES"?"0.6000":"0.4000",
        time_in_force:"immediate_or_cancel",
        post_only:false,
        self_trade_prevention_type:"taker_at_cross",
        cancel_order_on_pause:true
      };
      const r=await kalshi("POST","/portfolio/events/orders",order);
      const o=r.order||r;
      return res.status(200).json({
        clientId:order.client_order_id,
        orderId:o.order_id||null,
        fillCount:o.fill_count??null
      });
    }
    return reject(res,400,"UNSUPPORTED_ACTION");
  }catch(e){
    const status=Number(e.status)||500;
    return reject(res,status,status===500?"SERVER_ERROR":e.message);
  }
};
