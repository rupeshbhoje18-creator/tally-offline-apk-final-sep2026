package com.example.tallycustomerapp.web

/**
 * JavaScript collector injected into the authenticated Tally browser session.
 * It never invents accounting values. It reads visible HTML tables and sends
 * only rows that can be classified as ledger, voucher or stock data.
 */
object SyncCollector {
    fun script(): String = """
        (function() {
            if (window.__tallySyncCollectorInstalled) {
                if (window.TallyOfflineCollector) window.TallyOfflineCollector.scanAndSend();
                return;
            }
            window.__tallySyncCollectorInstalled = true;

            const STATUS_ID = 'tally-offline-sync-status';
            const BUTTON_ID = 'tally-offline-sync-button';
            const CHUNK_SIZE = 180000;

            function clean(v) {
                return (v || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
            }

            function number(v) {
                if (v === null || v === undefined) return 0;
                const s = String(v).replace(/,/g, '').replace(/₹/g, '').trim();
                if (!s) return 0;
                const neg = /^-/.test(s) || /\(/.test(s);
                const n = parseFloat(s.replace(/[^0-9.\\-]/g, ''));
                return Number.isFinite(n) ? (neg ? -Math.abs(n) : n) : 0;
            }

            function hash(v) {
                let h = 2166136261;
                for (let i = 0; i < v.length; i++) {
                    h ^= v.charCodeAt(i);
                    h = Math.imul(h, 16777619);
                }
                return (h >>> 0).toString(16);
            }

            function setStatus(text) {
                const el = document.getElementById(STATUS_ID);
                if (el) el.textContent = text;
                if (window.AndroidBridge && window.AndroidBridge.setSyncStatus) {
                    window.AndroidBridge.setSyncStatus(String(text));
                }
            }

            function getCompanyContext() {
                let companyName = '';
                let serialNumber = '';
                const tables = Array.from(document.querySelectorAll('table'));
                for (const table of tables) {
                    const text = clean(table.innerText);
                    if (/Company\s*Name/i.test(text) && /Connection\s*Status/i.test(text)) {
                        const row = Array.from(table.querySelectorAll('tbody tr')).find(r => /CONNECTED/i.test(r.innerText));
                        if (row) {
                            const cells = Array.from(row.querySelectorAll('td')).map(x => clean(x.innerText));
                            companyName = cells.find((x, i) => i > 0 && x && !/CONNECTED|OFFLINE|STATUS/i.test(x)) || '';
                            serialNumber = cells[2] || '';
                            break;
                        }
                    }
                }

                if (!companyName) {
                    const selectors = [
                        '[data-company-name]', '.company-name', '#companyName',
                        '[class*="company-name"]', '[class*="companyName"]'
                    ];
                    for (const s of selectors) {
                        const el = document.querySelector(s);
                        if (el && clean(el.textContent)) { companyName = clean(el.textContent); break; }
                    }
                }

                if (!companyName) {
                    const title = clean(document.title);
                    companyName = title
                        .replace(/TallyPrime|Tally|Reports?|Dashboard|Customer Portal/ig, '')
                        .replace(/[|:-]+/g, ' ')
                        .trim();
                }

                return { companyName: companyName || 'Tally Company', serialNumber: serialNumber || ('WEB-' + hash(companyName || location.href)) };
            }

            function headerMap(table) {
                const rows = table.querySelectorAll('tr');
                if (!rows.length) return null;
                let headerRow = null;
                for (let i = 0; i < Math.min(rows.length, 5); i++) {
                    const cells = Array.from(rows[i].querySelectorAll('th,td')).map(x => clean(x.innerText));
                    if (cells.length >= 2 && cells.some(x => /particular|ledger|account|stock|item|voucher|date|narration/i.test(x))) {
                        headerRow = rows[i];
                        break;
                    }
                }
                if (!headerRow) return null;
                const names = Array.from(headerRow.querySelectorAll('th,td')).map(x => clean(x.innerText).toLowerCase());
                return { names, startIndex: Array.from(rows).indexOf(headerRow) + 1, rows };
            }

            function idx(names, patterns) {
                for (const p of patterns) {
                    const i = names.findIndex(n => p.test(n));
                    if (i >= 0) return i;
                }
                return -1;
            }

            function collectTable(table, context) {
                const hm = headerMap(table);
                if (!hm) return null;
                const { names, startIndex, rows } = hm;

                const dateI = idx(names, [/date/i]);
                const voucherNoI = idx(names, [/voucher\s*(no|number)/i, /^no\.?$/i]);
                const voucherTypeI = idx(names, [/voucher\s*type/i, /^type$/i]);
                const partyI = idx(names, [/party|ledger|account|particular/i]);
                const narrationI = idx(names, [/narration|remarks|description/i]);
                const debitI = idx(names, [/^debit$/i, /debit\s*amount/i]);
                const creditI = idx(names, [/^credit$/i, /credit\s*amount/i]);
                const amountI = idx(names, [/amount|value|balance|total/i]);
                const openingI = idx(names, [/opening/i]);
                const closingI = idx(names, [/closing/i]);
                const qtyI = idx(names, [/closing\s*qty|quantity|qty/i]);
                const rateI = idx(names, [/closing\s*rate|rate/i]);
                const valueI = idx(names, [/closing\s*value|value/i]);
                const parentI = idx(names, [/under|parent|group/i]);
                const unitI = idx(names, [/unit/i]);

                const rowValues = [];
                for (let r = startIndex; r < rows.length; r++) {
                    const cells = Array.from(rows[r].querySelectorAll('td,th')).map(x => clean(x.innerText));
                    if (cells.length < 2) continue;
                    rowValues.push(cells);
                }
                if (!rowValues.length) return null;

                const looksVoucher = dateI >= 0 && (voucherTypeI >= 0 || voucherNoI >= 0 || narrationI >= 0);
                const looksStock = qtyI >= 0 && (valueI >= 0 || rateI >= 0) && idx(names, [/stock|item|particular/i]) >= 0;
                const looksLedger = partyI >= 0 && (closingI >= 0 || openingI >= 0) && !looksVoucher && !looksStock;

                if (looksVoucher) {
                    const vouchers = rowValues.map(cells => {
                        const date = dateI >= 0 ? clean(cells[dateI]) : '';
                        const voucherType = voucherTypeI >= 0 ? clean(cells[voucherTypeI]) : 'Voucher';
                        const voucherNumber = voucherNoI >= 0 ? clean(cells[voucherNoI]) : '';
                        const partyName = partyI >= 0 ? clean(cells[partyI]) : '';
                        const narration = narrationI >= 0 ? clean(cells[narrationI]) : '';
                        let amount = amountI >= 0 ? number(cells[amountI]) : 0;
                        if (!amount && (debitI >= 0 || creditI >= 0)) amount = number(cells[debitI >= 0 ? debitI : creditI]);
                        const guid = 'web-v:' + hash([date, voucherType, voucherNumber, partyName, narration, amount].join('|'));
                        const entryName = partyName || 'Tally Voucher';
                        return {
                            guid, date, voucherType, voucherNumber: voucherNumber || null,
                            partyName: partyName || null, amount, narration: narration || null,
                            alteredOn: null,
                            entries: [{ ledgerGuid: null, ledgerName: entryName, amount, isDeemedPositive: false }]
                        };
                    }).filter(v => v.date || v.voucherNumber || v.partyName || v.amount);
                    return vouchers.length ? { type: 'vouchers', vouchers } : null;
                }

                if (looksStock) {
                    const stockNameI = idx(names, [/stock\s*item|item|particular/i]);
                    const stockItems = rowValues.map(cells => {
                        const name = stockNameI >= 0 ? clean(cells[stockNameI]) : '';
                        if (!name || /total|opening|closing/i.test(name)) return null;
                        const closingQty = qtyI >= 0 ? number(cells[qtyI]) : 0;
                        const closingValue = valueI >= 0 ? number(cells[valueI]) : 0;
                        const closingRate = rateI >= 0 ? number(cells[rateI]) : 0;
                        const guid = 'web-s:' + hash([name, closingQty, closingValue, closingRate].join('|'));
                        return { guid, name, parent: parentI >= 0 ? (clean(cells[parentI]) || null) : null,
                            unit: unitI >= 0 ? (clean(cells[unitI]) || null) : null,
                            openingQty: 0, closingQty, openingValue: 0, closingValue, closingRate, alteredOn: null };
                    }).filter(Boolean);
                    return stockItems.length ? { type: 'stockItems', stockItems } : null;
                }

                if (looksLedger) {
                    const ledgerNameI = idx(names, [/ledger\s*name|particular|ledger|account/i]);
                    const ledgers = rowValues.map(cells => {
                        const name = ledgerNameI >= 0 ? clean(cells[ledgerNameI]) : '';
                        if (!name || /total|opening|closing/i.test(name)) return null;
                        const openingBalance = openingI >= 0 ? number(cells[openingI]) : 0;
                        const closingBalance = closingI >= 0 ? number(cells[closingI]) : (amountI >= 0 ? number(cells[amountI]) : 0);
                        const guid = 'web-l:' + hash([name, openingBalance, closingBalance].join('|'));
                        return { guid, name, parent: parentI >= 0 ? (clean(cells[parentI]) || null) : null,
                            openingBalance, closingBalance, alteredOn: null };
                    }).filter(Boolean);
                    return ledgers.length ? { type: 'ledgers', ledgers } : null;
                }
                return null;
            }

            function buildPayload() {
                const context = getCompanyContext();
                const result = { companyName: context.companyName, serialNumber: context.serialNumber,
                    gstin: null, financialYearFrom: null, replaceAll: false,
                    collectedSections: [], ledgers: [], vouchers: [], stockItems: [] };

                const all = Array.from(document.querySelectorAll('table'));
                const seen = new Set();
                for (const table of all) {
                    const collected = collectTable(table, context);
                    if (!collected) continue;
                    if (collected.type === 'ledgers') result.ledgers.push(...collected.ledgers);
                    if (collected.type === 'vouchers') result.vouchers.push(...collected.vouchers);
                    if (collected.type === 'stockItems') result.stockItems.push(...collected.stockItems);
                }

                result.ledgers = result.ledgers.filter(x => { const k = x.guid; if (seen.has(k)) return false; seen.add(k); return true; });
                result.vouchers = result.vouchers.filter(x => { const k = x.guid; if (seen.has(k)) return false; seen.add(k); return true; });
                result.stockItems = result.stockItems.filter(x => { const k = x.guid; if (seen.has(k)) return false; seen.add(k); return true; });
                if (result.ledgers.length) result.collectedSections.push('ledgers');
                if (result.vouchers.length) result.collectedSections.push('vouchers');
                if (result.stockItems.length) result.collectedSections.push('stockItems');
                return result;
            }

            function sendPayload(payload) {
                if (!window.AndroidBridge) { setStatus('Android bridge not available'); return; }
                const raw = JSON.stringify(payload);
                if (raw.length <= CHUNK_SIZE && window.AndroidBridge.saveCompanyDataOffline) {
                    window.AndroidBridge.saveCompanyDataOffline(raw);
                    return;
                }
                if (!window.AndroidBridge.beginSync || !window.AndroidBridge.pushSyncChunk || !window.AndroidBridge.commitSync) {
                    setStatus('Sync payload too large for current bridge');
                    return;
                }
                const syncId = 'sync-' + Date.now();
                window.AndroidBridge.beginSync(syncId);
                for (let i = 0; i < raw.length; i += CHUNK_SIZE) {
                    window.AndroidBridge.pushSyncChunk(syncId, raw.substring(i, Math.min(i + CHUNK_SIZE, raw.length)));
                }
                window.AndroidBridge.commitSync(syncId);
            }

            function scanAndSend() {
                try {
                    setStatus('Collecting visible Tally data…');
                    const payload = buildPayload();
                    const total = payload.ledgers.length + payload.vouchers.length + payload.stockItems.length;
                    if (!total) {
                        setStatus('No Ledger/Voucher/Stock table found on this page');
                        return;
                    }
                    setStatus('Saving ' + total + ' records…');
                    sendPayload(payload);
                } catch (e) {
                    setStatus('Collector error: ' + (e && e.message ? e.message : e));
                }
            }

            function installUi() {
                if (!document.body) return;
                if (!document.getElementById(BUTTON_ID)) {
                    const btn = document.createElement('button');
                    btn.id = BUTTON_ID;
                    btn.textContent = 'SYNC TALLY DATA';
                    btn.style.cssText = 'position:fixed;top:12px;right:12px;z-index:2147483647;background:#1565c0;color:#fff;border:none;padding:10px 16px;border-radius:8px;font-weight:700;box-shadow:0 2px 8px rgba(0,0,0,.25);cursor:pointer;';
                    btn.onclick = function(e) { e.preventDefault(); e.stopPropagation(); scanAndSend(); };
                    document.body.appendChild(btn);
                }
                if (!document.getElementById(STATUS_ID)) {
                    const st = document.createElement('div');
                    st.id = STATUS_ID;
                    st.textContent = 'Ready to sync visible Tally data';
                    st.style.cssText = 'position:fixed;top:56px;right:12px;z-index:2147483647;background:rgba(255,255,255,.96);color:#222;border:1px solid #ddd;padding:6px 10px;border-radius:6px;font:12px sans-serif;max-width:320px;';
                    document.body.appendChild(st);
                }
            }

            window.TallyOfflineCollector = { scanAndSend, buildPayload };
            installUi();
            setTimeout(installUi, 1200);
            setTimeout(installUi, 3000);
        })();
    """.trimIndent()
}
