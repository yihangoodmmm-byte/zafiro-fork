import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

// Exercises the shipped JavaScript bodies, not a second implementation. This is not WebView/device QA.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const file = path.join(root, 'app/src/main/java/com/niki914/zafiro/app/browser/BrowserScripts.kt');
const source = fs.readFileSync(file, 'utf8');
function body(name) {
  const expression = new RegExp('fun ' + name + '\\([^]*?= """([^]*?)"""');
  const match = source.match(expression);
  assert.ok(match, 'Missing script: ' + name);
  return match[1];
}
function render(name, values = {}) {
  const code = body(name).replace(/@@([A-Z]+)@@/g, (_, token) => {
    assert.ok(token in values, 'Missing substitution ' + token);
    return JSON.stringify(values[token]);
  });
  new vm.Script(code);
  return code;
}
function fixture() {
  let clicks = 0;
  let submissions = 0;
  let currentValue = '';
  class Input { }
  Object.defineProperty(Input.prototype, 'value', { get() { return currentValue; }, set(value) { currentValue = value; } });
  class Area { }
  Object.defineProperty(Area.prototype, 'value', { set(value) { currentValue = value; } });
  class Mutation {
    constructor(callback) { this.callback = callback; this.pending = []; }
    observe() { }
    disconnect() { }
    takeRecords() { const result = this.pending; this.pending = []; return result; }
  }
  const node = new Input();
  Object.assign(node, {
    tagName:'INPUT', disabled:false, isConnected:true, tabIndex:0,
    innerText:'Field', attributes:{},
    getAttribute(name) { return this.attributes[name] ?? null; },
    getBoundingClientRect() { return {x:10,y:20,width:100,height:30}; },
    getClientRects() { return [1]; },
    scrollIntoView() { }, contains(other) { return other === this; },
    focus() { }, dispatchEvent() { }, click() { clicks++; },
    form: { requestSubmit() { submissions++; } },
  });
  const document = { documentElement:{}, body:{innerText:'Hello browser'}, querySelectorAll:()=>[node], elementFromPoint:()=>node };
  const context = vm.createContext({ window:{}, location:{href:'https://example.com/'}, document,
    getComputedStyle:()=>({display:'block',visibility:'visible'}),
    HTMLInputElement:Input, HTMLTextAreaElement:Area, MutationObserver:Mutation,
    Event:class { constructor(type, options) { this.type=type;this.options=options; } },
    KeyboardEvent:class { },
  });
  const run = (name, values) => JSON.parse(vm.runInContext(render(name, values), context));
  const snapshot = () => run('snapshot', {TOKEN:'fixture-token'});
  const action = (command, extras={}) => run('action', {TOKEN:'fixture-token',REF:'fixture-token-1',COMMAND:command,TEXT:'value',REPLACE:true,KEY:'Enter',...extras});
  return {node,document,context,run,snapshot,action,clicks:()=>clicks,submissions:()=>submissions,value:()=>currentValue};
}
const cases = [];
function test(name, fn) { fn(); cases.push(name); }
test('snapshot produces owner observation refs and bounded metadata', () => { const f=fixture(); const result=f.snapshot(); assert.equal(result.nodes[0].ref,'fixture-token-1'); assert.equal(result.nodes[0].role,'input'); });
test('snapshot caps node count at 350', () => { const f=fixture(); f.document.querySelectorAll=()=>Array(400).fill(f.node); const result=f.snapshot(); assert.equal(result.nodes.length,350); assert.equal(result.truncated,true); });
test('click dispatches once on a current visible node', () => { const f=fixture();f.snapshot();assert.equal(f.action('click').ok,true);assert.equal(f.clicks(),1); });
test('detached node cannot dispatch', () => { const f=fixture();f.snapshot();f.node.isConnected=false;assert.equal(f.action('click').code,'STALE_REF');assert.equal(f.clicks(),0); });
test('changed URL invalidates refs without a new page callback', () => { const f=fixture();f.snapshot();f.context.location.href+='next';assert.equal(f.action('click').code,'STALE_REF'); });
test('mutation records invalidate refs synchronously', () => { const f=fixture();f.snapshot();f.context.window.__zafiroObservation.observer.pending.push({});assert.equal(f.action('click').code,'STALE_REF');assert.equal(f.clicks(),0); });
test('disabled node cannot dispatch', () => { const f=fixture();f.snapshot();f.node.disabled=true;assert.equal(f.action('click').code,'NODE_DISABLED'); });
test('covered node cannot dispatch', () => { const f=fixture();f.snapshot();f.document.elementFromPoint=()=>({});assert.equal(f.action('click').code,'NODE_OBSCURED'); });
test('typing replaces and appends through native value setter', () => { const f=fixture();f.snapshot();assert.equal(f.action('type',{TEXT:'中文'}).ok,true);assert.equal(f.value(),'中文');assert.equal(f.action('type',{TEXT:'追加',REPLACE:false}).ok,true);assert.equal(f.value(),'中文追加'); });
test('quoted text is data and cannot become JavaScript', () => { const f=fixture();f.snapshot();const text='";throw new Error("injected");// @@KEY@@';assert.equal(f.action('type',{TEXT:text}).ok,true);assert.equal(f.value(),text); });
test('Enter submits a real form while unsupported keys return failure', () => { const f=fixture();f.snapshot();assert.equal(f.action('press').ok,true);assert.equal(f.submissions(),1);assert.equal(f.action('press',{KEY:'Escape'}).code,'KEY_UNSUPPORTED'); });
test('text extraction is bounded and reports truncation', () => { const f=fixture();f.document.body.innerText='x'.repeat(60000);const result=f.run('text');assert.equal(result.text.length,50000);assert.equal(result.truncated,true); });
console.log(JSON.stringify({suite:'browser-script-logic',passed:cases.length,cases,notCovered:['Android WebView','Profile isolation','native view lifecycle','Android build']},null,2));
