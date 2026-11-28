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
const sign=(code,body,ts=Date.now().toString())=>{
  const raw=JSON.stringify(body);
  const digest=crypto.createHash("sha256").update(raw).digest("hex");
  const message=[ts,"POST","/api/kalshi",digest].join("\n");
  const signature=crypto.createHmac("sha256",Buffer.from(code,"utf8"))
    .update(message).digest("base64");
  return {"x-trader-timestamp":ts,"x-trader-signature":signature};
};

test("access is denied when permanent owner code is not configured",async()=>{
  const old=process.env.KALSHI_OWNER_ACCESS_CODE;
  delete process.env.KALSHI_OWNER_ACCESS_CODE;
  try {
    const response=await call({action:"balance"});
    assert.equal(response.statusCode,503);
    assert.equal(response.body.error,"OWNER_ACCESS_NOT_CONFIGURED");
  } finally {
    if(old===undefined)delete process.env.KALSHI_OWNER_ACCESS_CODE;
    else process.env.KALSHI_OWNER_ACCESS_CODE=old;
  }
});
test("permanent owner code is used for EVERY request and never exposed",async()=>{
  const oldCode=process.env.KALSHI_OWNER_ACCESS_CODE;
  const oldId=process.env.KALSHI_API_ID;
  const oldPem=process.env.KALSHI_API_KEY;
  const code="test-PERSISTENT-Owner×Access÷Private-2026";
  try{
    process.env.KALSHI_OWNER_ACCESS_CODE=code;
    delete process.env.KALSHI_API_ID;
    delete process.env.KALSHI_API_KEY;
    const healthBody={action:"check_auth"};
    const healthy=await call(healthBody,sign(code,healthBody));
    assert.equal(healthy.statusCode,200);
    assert.equal(healthy.body.authenticated,true);
    assert.equal(JSON.stringify(healthy.body).includes(code),false);
    const body={action:"balance"};
    for(let attempt=0;attempt<2;attempt++){
      const ok=await call(body,sign(code,body));
      assert.equal(ok.statusCode,503); // Authorized, but Kalshi credentials absent.
      assert.equal(ok.body.error,"KALSHI_NOT_CONFIGURED");
      assert.equal(JSON.stringify(ok.body).includes(code),false);
    }
    const wrong=await call(body,sign("different-test-owner-key-123456",body));
    assert.equal(wrong.statusCode,401);
    const changed=await call({action:"order",count:1},sign(code,body));
    assert.equal(changed.statusCode,401);
    const stale=await call(body,sign(code,body,(Date.now()-120000).toString()));
    assert.equal(stale.statusCode,401);
    const empty=await call(body);
    assert.equal(empty.statusCode,401);
  }finally{
    if(oldCode===undefined)delete process.env.KALSHI_OWNER_ACCESS_CODE;
    else process.env.KALSHI_OWNER_ACCESS_CODE=oldCode;
    if(oldId===undefined)delete process.env.KALSHI_API_ID;
    else process.env.KALSHI_API_ID=oldId;
    if(oldPem===undefined)delete process.env.KALSHI_API_KEY;
    else process.env.KALSHI_API_KEY=oldPem;
  }
});