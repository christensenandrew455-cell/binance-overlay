const test=require("node:test");
const assert=require("node:assert/strict");
const crypto=require("node:crypto");
const handler=require("../api/kalshi.js");

async function call(body={},headers={}) {
  const out={statusCode:200,body:null,headers:{}};
  const res={
    setHeader(key,value){out.headers[key]=value;return this},
    status(statusCode){out.statusCode=statusCode;return this},
    json(value){out.body=value;return this}
  };
  await handler({method:"POST",body:JSON.stringify(body),headers},res);
  return out;
}
test("unpaired requests fail before any Kalshi secrets are used",async()=>{
  const saved=process.env.KALSHI_DEVICE_PUBLIC_KEY;
  delete process.env.KALSHI_DEVICE_PUBLIC_KEY;
  try {
    const r=await call({action:"balance"});
    assert.equal(r.statusCode,403);
    assert.equal(r.body.error,"PHONE_NOT_PAIRED");
  }finally {
    if(saved!==undefined)process.env.KALSHI_DEVICE_PUBLIC_KEY=saved;
  }
});
test("signed request is accepted only for registered device public key",async()=>{
  const {publicKey,privateKey}=crypto.generateKeyPairSync("rsa",{modulusLength:2048});
  const savedKey=process.env.KALSHI_DEVICE_PUBLIC_KEY;
  const savedId=process.env.KALSHI_API_ID;
  const savedPem=process.env.KALSHI_API_KEY;
  try{
    process.env.KALSHI_DEVICE_PUBLIC_KEY=publicKey.export({format:"der",type:"spki"}).toString("base64");
    delete process.env.KALSHI_API_ID;
    delete process.env.KALSHI_API_KEY;
    const body=JSON.stringify({action:"balance"});
    const stamp=Date.now().toString();
    const signed=[stamp,"POST","/api/kalshi",crypto.createHash("sha256").update(body).digest("hex")].join("\n");
    const sig=crypto.sign("sha256",Buffer.from(signed),privateKey).toString("base64");
    const h={"x-trader-timestamp":stamp,"x-trader-signature":sig};
    const result=await call({action:"balance"},h);
    assert.equal(result.statusCode,503); // authorization passed; creds intentionally missing
    assert.equal(result.body.error,"KALSHI_NOT_CONFIGURED");
    const forged=await call({action:"order",count:1},h);
    assert.equal(forged.statusCode,401);
    const stale=await call({action:"balance"},{
      ...h,"x-trader-timestamp":(Date.now()-120000).toString()
    });
    assert.equal(stale.statusCode,401);
  }finally{
    if(savedKey===undefined)delete process.env.KALSHI_DEVICE_PUBLIC_KEY;
    else process.env.KALSHI_DEVICE_PUBLIC_KEY=savedKey;
    if(savedId===undefined)delete process.env.KALSHI_API_ID;
    else process.env.KALSHI_API_ID=savedId;
    if(savedPem===undefined)delete process.env.KALSHI_API_KEY;
    else process.env.KALSHI_API_KEY=savedPem;
  }
});
