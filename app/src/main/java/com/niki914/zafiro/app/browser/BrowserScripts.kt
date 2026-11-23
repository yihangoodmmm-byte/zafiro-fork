package com.niki914.zafiro.app.browser

import org.json.JSONObject

/** No JavascriptInterface is installed. These scripts return bounded, untrusted page observations. */
internal object BrowserScripts {
    fun snapshot(token: String): String = """
        (() => {
          const token = @@TOKEN@@;
          const nodes = [];
          const refs = new Map();
          let index = 0;
          const candidates = document.querySelectorAll('a[href],button,input,textarea,select,[role],[contenteditable="true"],summary');
          for (const element of candidates) {
            if (nodes.length >= 350) break;
            const rect = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            if (rect.width <= 0 || rect.height <= 0 || style.display === 'none' || style.visibility === 'hidden') continue;
            const ref = token + '-' + (++index);
            const tag = element.tagName.toLowerCase();
            const role = element.getAttribute('role') || (tag === 'a' ? 'link' : tag);
            const name = element.getAttribute('aria-label') || element.getAttribute('alt') || element.getAttribute('placeholder') || element.innerText || element.value || '';
            refs.set(ref, element);
            nodes.push({ref, role, name: String(name).slice(0, 240), enabled: !element.disabled && element.getAttribute('aria-disabled') !== 'true', bounds:[rect.x,rect.y,rect.width,rect.height]});
          }
          if(window.__zafiroObservation && window.__zafiroObservation.observer) window.__zafiroObservation.observer.disconnect();
          const observation = {token,refs,url:location.href,dirty:false};
          const observer = new MutationObserver(()=>{observation.dirty=true;});
          observer.observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});
          observation.observer=observer;
          window.__zafiroObservation=observation;
          return JSON.stringify({ok:true, nodes, truncated:candidates.length > 350});
        })()
    """.trimIndent().let { render(it, mapOf("TOKEN" to token)) }

    fun text(): String = """
        (()=>{const text=document.body ? document.body.innerText : ''; return JSON.stringify({ok:true,text:text.slice(0,50000),truncated:text.length>50000});})()
    """.trimIndent()

    fun action(token: String, ref: String, command: String, text: String, replace: Boolean, key: String): String = """
        (() => {
          const observation=window.__zafiroObservation;
          const fail=(code,message)=>JSON.stringify({ok:false,code,message});
          if(!observation || observation.token !== @@TOKEN@@) return fail('STALE_REF','Take a new snapshot.');
          if(observation.dirty || observation.observer.takeRecords().length) return fail('STALE_REF','The DOM changed. Take a new snapshot.');
          if(observation.url!==location.href) return fail('STALE_REF','Page location changed.');
          const element=observation.refs.get(@@REF@@);
          if(!element || !element.isConnected) return fail('STALE_REF','Node is detached. Take a new snapshot.');
          if(element.disabled || element.getAttribute('aria-disabled')==='true') return fail('NODE_DISABLED','Node is disabled.');
          const rect=element.getBoundingClientRect();
          const style=getComputedStyle(element);
          if(rect.width<=0 || rect.height<=0 || style.display==='none' || style.visibility==='hidden') return fail('NODE_HIDDEN','Node is hidden.');
          element.scrollIntoView({block:'center',inline:'nearest'});
          const current=element.getBoundingClientRect();
          const hit=document.elementFromPoint(current.x+current.width/2,current.y+current.height/2);
          if(!hit || (hit!==element && !element.contains(hit))) return fail('NODE_OBSCURED','Node is covered by another element.');
          const command=@@COMMAND@@;
          if(command==='click') element.click();
          else if(command==='type') {
            element.focus();
            const input=@@TEXT@@;
            if(element.tagName==='INPUT' || element.tagName==='TEXTAREA') {
              const next=@@REPLACE@@ ? input : element.value+input;
              const proto=element.tagName==='INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
              const setter=Object.getOwnPropertyDescriptor(proto,'value').set;
              setter.call(element,next);
              if(element.value!==next) return fail('VALUE_REJECTED','Input rejected this value.');
            } else if(element.isContentEditable) element.textContent=@@REPLACE@@ ? input : element.textContent+input;
            else return fail('NODE_NOT_EDITABLE','Node is not editable.');
            element.dispatchEvent(new Event('input',{bubbles:true}));
            element.dispatchEvent(new Event('change',{bubbles:true}));
          } else if(command==='press') {
            element.focus(); const key=@@KEY@@;
            if(key==='Enter' && element.form && element.form.requestSubmit) element.form.requestSubmit();
            else if(key==='Tab') {
              const focusable=Array.from(document.querySelectorAll('a[href],button,input,textarea,select,[tabindex]')).filter(e=>!e.disabled && e.tabIndex>=0 && e.getClientRects().length);
              const index=focusable.indexOf(element); if(index>=0 && focusable.length) focusable[(index+1)%focusable.length].focus();
            } else return fail('KEY_UNSUPPORTED','This node does not support that key. Only Tab focus or Enter form submission is implemented.');
          }
          return JSON.stringify({ok:true});
        })()
    """.trimIndent().let { script ->
        render(script, mapOf("TOKEN" to token, "REF" to ref, "COMMAND" to command, "TEXT" to text, "KEY" to key), replace)
    }

    private fun render(script: String, values: Map<String, String>, replace: Boolean = true): String =
        Regex("@@([A-Z]+)@@").replace(script) { match ->
            if (match.groupValues[1] == "REPLACE") replace.toString()
            else JSONObject.quote(values.getValue(match.groupValues[1]))
        }
}
