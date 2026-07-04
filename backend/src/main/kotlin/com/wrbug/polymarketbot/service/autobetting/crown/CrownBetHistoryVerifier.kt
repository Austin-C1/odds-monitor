package com.wrbug.polymarketbot.service.autobetting.crown

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.service.autobetting.CrownBetPlacementCommand
import com.wrbug.polymarketbot.service.autobetting.CrownBetPlacementResult
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerCdpClient
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerLocalApiClient

class CrownBetHistoryVerifier(
    private val apiClient: AdsPowerLocalApiClient,
    private val cdpClient: AdsPowerCdpClient,
    private val objectMapper: ObjectMapper
) {
    fun verifyPlacedBet(command: CrownBetPlacementCommand, ticketReference: String?): CrownBetPlacementResult {
        val active = apiClient.checkProfileActive(command.profileId)
        if (!active.opened) {
            return CrownBetPlacementResult(true, false, ticketReference, "profile_not_opened")
        }
        val debugPort = active.debugPort?.trim().orEmpty()
        if (debugPort.isBlank()) {
            return CrownBetPlacementResult(true, false, ticketReference, "browser_debug_port_missing")
        }
        cdpClient.closeCrownPrintTargets(debugPort)
        val target = cdpClient.readCrownPageTarget(debugPort, command.loginUrl)
            ?: return CrownBetPlacementResult(true, false, ticketReference, "crown_page_not_found")
        val initialWsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
        if (initialWsUrl.isBlank()) {
            return CrownBetPlacementResult(true, false, ticketReference, "crown_debug_target_missing")
        }
        cdpClient.installCrownPrintGuard(initialWsUrl)
        val activated = cdpClient.activateCrownPageBeforePlacement(debugPort, target, command.loginUrl)
            ?: return CrownBetPlacementResult(true, false, ticketReference, "crown_page_activation_failed")
        val wsUrl = activated.wsUrl
        cdpClient.installCrownPrintGuard(wsUrl)
        val session = CrownSessionPageAnalyzer.analyze(activated.snapshot.text, activated.snapshot.title)
        if (!session.loggedIn) {
            if (session.accountStatus == CROWN_NETWORK_UNSTABLE_STATUS) {
                cdpClient.dismissCrownNetworkPrompt(wsUrl)
            }
            return CrownBetPlacementResult(true, false, ticketReference, session.accountStatus)
        }

        val argsJson = objectMapper.writeValueAsString(command)
        val ticketReferenceJson = objectMapper.writeValueAsString(ticketReference.orEmpty())
        val resultText = cdpClient.evaluateCrownPageJson(wsUrl, crownBetHistoryVerificationScript(argsJson, ticketReferenceJson))
            ?: return CrownBetPlacementResult(true, false, ticketReference, "crown_history_unverified")
        val result = runCatching { objectMapper.readTree(resultText) }.getOrNull()
            ?: return CrownBetPlacementResult(true, false, ticketReference, "crown_execution_parse_failed")
        cdpClient.dispatchNativeReceiptOkClick(wsUrl, result)
        return CrownBetPlacementResult(
            placed = true,
            historyVerified = result.path("historyVerified").asBoolean(false),
            ticketReference = result.path("ticketReference").textOrNull() ?: ticketReference,
            message = result.path("message").textOrNull() ?: "crown_history_unverified"
        )
    }

    internal fun crownBetHistoryVerificationScript(argsJson: String, ticketReferenceJson: String): String {
        return """
            (async () => {
              const args = $argsJson;
              const ticketReference = $ticketReferenceJson;
              const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
              const accessibleWindows = () => {
                const windows = [];
                const seen = new Set();
                const visit = (win) => {
                  if (!win || seen.has(win)) return;
                  seen.add(win);
                  try {
                    if (win.document) windows.push(win);
                    for (const frame of Array.from(win.frames || [])) visit(frame);
                  } catch (_) {
                    // Ignore cross-origin frames.
                  }
                };
                visit(window);
                return windows;
              };
              const documents = () => accessibleWindows().map((win) => win.document).filter(Boolean);
              const byId = (id) => {
                for (const doc of documents()) {
                  const element = doc.getElementById(id);
                  if (element) return element;
                }
                return null;
              };
              const ownerWindow = (element) => element?.ownerDocument?.defaultView || window;
              const isVisible = (element) => Boolean(element && (() => {
                const view = ownerWindow(element);
                const style = view.getComputedStyle(element);
                return style.display !== 'none'
                  && style.visibility !== 'hidden'
                  && style.visibility !== 'collapse'
                  && style.opacity !== '0'
                  && (element.offsetWidth || element.offsetHeight || element.getClientRects().length);
              })());
              const allSelector = (selector) => documents().flatMap((doc) => Array.from(doc.querySelectorAll(selector)));
              const clickElement = (element) => {
                if (!element) return false;
                const view = ownerWindow(element);
                const MouseEventCtor = view.MouseEvent || window.MouseEvent;
                try { element.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
                const rect = element.getBoundingClientRect();
                const x = rect.left + rect.width / 2;
                const y = rect.top + rect.height / 2;
                const target = element.ownerDocument?.elementFromPoint?.(x, y) || element;
                for (const type of ['mouseover', 'mousemove', 'mousedown', 'mouseup', 'click']) {
                  try {
                    target.dispatchEvent(new MouseEventCtor(type, {
                      bubbles: true,
                      cancelable: true,
                      view,
                      clientX: x,
                      clientY: y,
                      button: 0,
                      buttons: type === 'mousedown' ? 1 : 0
                    }));
                  } catch (_) {}
                }
                try { target.click?.(); } catch (_) {}
                if (target !== element) {
                  try { element.click?.(); } catch (_) {}
                }
                return true;
              };
              const nativePoint = (element) => {
                if (!element) return null;
                try { element.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
                const rect = element.getBoundingClientRect();
                if (!rect || rect.width <= 0 || rect.height <= 0) return null;
                let left = rect.left + rect.width / 2;
                let top = rect.top + rect.height / 2;
                let currentWindow = ownerWindow(element);
                for (let depth = 0; currentWindow && currentWindow !== window && depth < 8; depth += 1) {
                  const frame = currentWindow.frameElement;
                  if (!frame) break;
                  const frameRect = frame.getBoundingClientRect();
                  left += frameRect.left;
                  top += frameRect.top;
                  currentWindow = currentWindow.parent;
                }
                return { x: left, y: top };
              };
              const text = () => documents().map((doc) => doc.body?.innerText || '').join('\n');
              const rawText = () => documents().map((doc) => doc.documentElement?.innerText || '').join('\n');
              const menuCandidateIds = () => {
                const phase = String(args.matchPhase || 'live').toLowerCase();
                if (phase === 'prematch') {
                  return ['today_page', 'old_ft_league', 'ft_league', 'old_ft_today_league', 'ft_today_league', 'today'];
                }
                return ['old_ft_live_league', 'ft_live_league', 'live_page'];
              };
              const returnToFootballPage = async () => {
                for (const id of menuCandidateIds()) {
                  const element = byId(id);
                  if (element && isVisible(element)) {
                    clickElement(element);
                    await sleep(800);
                    return true;
                  }
                }
                return false;
              };
              const confirmBetIfPrompted = async () => {
                const prompts = [
                  { container: 'alert_confirm', yes: 'yes_btn', checkbox: 'confirm_chk' },
                  { container: 'C_alert_confirm', yes: 'C_yes_btn', checkbox: 'C_confirm_chk' }
                ];
                for (let index = 0; index < 20; index += 1) {
                  for (const prompt of prompts) {
                    const container = byId(prompt.container);
                    if (!isVisible(container)) continue;
                    const checkbox = byId(prompt.checkbox);
                    if (checkbox && !checkbox.checked) {
                      checkbox.checked = true;
                      const EventCtor = ownerWindow(checkbox).Event || window.Event;
                      checkbox.dispatchEvent(new EventCtor('change', { bubbles: true }));
                      await sleep(100);
                    }
                    const yesButton = byId(prompt.yes);
                    if (yesButton && isVisible(yesButton)) {
                      clickElement(yesButton);
                      await sleep(500);
                      return true;
                    }
                  }
                  await sleep(150);
                }
                return false;
              };
              const receiptSuccessMarker = /YOUR BETS HAVE BEEN SUCCESSFULLY PLACED|successfully placed|BET RECEIPT/i;
              const receiptOkButtonPoint = async () => {
                const receiptVisible = () => receiptSuccessMarker.test(text()) || receiptSuccessMarker.test(rawText()) || /Retain Selection/i.test(text());
                if (!receiptVisible()) return false;
                for (let attempt = 0; attempt < 8; attempt += 1) {
                  const candidates = [
                    byId('order_close'),
                    byId('btn_close'),
                    byId('close_btn'),
                    ...allSelector('.close,.btn_close,.order_close,button,[role="button"],a,div')
                  ].filter(Boolean);
                  const okButton = candidates.find((element) => {
                    if (!isVisible(element)) return false;
                    const label = String(element.innerText || element.textContent || element.value || '').trim();
                    const id = String(element.id || '');
                    const className = String(element.className || '');
                    const combined = [label, id, className].join(' ');
                    if (/My\s*Bets|Open\s*Bets|Statement|Wager/i.test(combined)) return false;
                    return label === 'OK' || /order_close|close|ok/i.test(id) || /order_close|close|ok/i.test(className);
                  });
                  if (okButton) {
                    return nativePoint(okButton);
                  }
                  await sleep(250);
                }
                return null;
              };
              const normalizeLine = (value) => String(value || '').replace(/\s+/g, '').replace(/[０-９]/g, (char) => String.fromCharCode(char.charCodeAt(0) - 65248));
              const extractReceiptReference = (value) => {
                const ticketMatch = String(value || '').match(/Ticket No:\s*([A-Za-z0-9-]+)/i);
                if (ticketMatch) return ticketMatch[1];
                const fantasyMatch = String(value || '').match(/\b(?:OU|HDP|FS|FT|BK)[A-Z0-9]*\d{6,}\b/i);
                return fantasyMatch ? fantasyMatch[0] : null;
              };
              const matchTokens = () => String(args.matchTitle || '')
                .split(/\s+vs\s+|\s+v\s+/i)
                .map((part) => part.trim().toLowerCase())
                .filter((part) => part.length >= 2);
              const stakeText = () => Number(args.stakeAmount).toFixed(2);
              const expectedOpenBetSide = () => {
                const market = String(args.marketType || '').toLowerCase();
                const selection = String(args.selectionName || '').toLowerCase();
                if (market === 'total') {
                  if (selection.includes('大') || selection.includes('over') || selection === 'o') return 'Over';
                  if (selection.includes('小') || selection.includes('under') || selection === 'u') return 'Under';
                }
                return null;
              };
              const expectedOpenBetSelection = () => {
                const market = String(args.marketType || '').toLowerCase();
                const selectionText = String(args.selectionName || '').trim();
                const selection = selectionText.toLowerCase();
                if (market !== 'handicap') return null;
                const tokens = matchTokens();
                if (selection === 'home' || selection === '主队') return tokens[0] || null;
                if (selection === 'away' || selection === '客队') return tokens[1] || null;
                return selectionText || null;
              };
              const historyVerified = (value) => {
                const content = String(value || '');
                const lower = content.toLowerCase();
                if (ticketReference && content.includes(ticketReference)) return true;
                if (receiptSuccessMarker.test(content) && extractReceiptReference(content)) return true;
                const tokens = matchTokens();
                if (tokens.length >= 2 && !tokens.every((token) => lower.includes(token))) return false;
                if (args.lineValue && !normalizeLine(content).includes(normalizeLine(args.lineValue))) return false;
                if (!content.includes(stakeText()) && !content.includes(String(Number(args.stakeAmount)))) return false;
                const side = expectedOpenBetSide();
                if (side && !(new RegExp('\\b' + side + '\\b', 'i')).test(content)) return false;
                const selection = expectedOpenBetSelection();
                if (selection && !lower.includes(selection.toLowerCase())) return false;
                return /OPEN BETS|Open Bets|Confirmed|Statement|My Bets|Ticket No:/i.test(content);
              };
              const parseMoney = (value) => {
                const match = String(value || '').replace(/,/g, '').match(/\d+(?:\.\d+)?/);
                return match ? Number(match[0]) : null;
              };
              const parseSelection = (selectionLine, marketType) => {
                const oddsMatch = String(selectionLine || '').match(/(.+?)\s*@\s*(-?\d+(?:\.\d+)?)/);
                const body = oddsMatch ? oddsMatch[1].trim() : String(selectionLine || '').trim();
                const odds = oddsMatch ? Number(oddsMatch[2]) : null;
                let selectionName = body;
                let lineValue = null;
                if (marketType === 'total') {
                  const totalMatch = body.match(/^(Over|Under|O|U)\s+(.+)$/i);
                  if (totalMatch) {
                    selectionName = /^u/i.test(totalMatch[1]) ? 'Under' : 'Over';
                    lineValue = totalMatch[2].replace(/\s+/g, ' ').trim();
                  }
                } else if (marketType === 'handicap') {
                  const handicapMatch = body.match(/^(.+?)\s+([+-]?\d+(?:\.\d+)?(?:\s*\/\s*[+-]?\d+(?:\.\d+)?)?)$/);
                  if (handicapMatch) {
                    selectionName = handicapMatch[1].trim();
                    lineValue = handicapMatch[2].replace(/\s+/g, ' ').trim();
                  }
                }
                return { selectionName, lineValue, odds };
              };
              const parseVerifiedBetRecord = (value) => {
                const lines = String(value || '').split('\n').map((line) => line.trim()).filter(Boolean);
                let leagueName = '';
                let matchTitle = '';
                let marketLine = '';
                let selectionLine = '';
                for (let index = 0; index < lines.length - 3; index += 1) {
                  if (/^Soccer\b/i.test(lines[index]) && /\s+v(?:s)?\s+/i.test(lines[index + 2]) && /@\s*-?\d+(?:\.\d+)?/.test(lines[index + 3])) {
                    marketLine = lines[index];
                    leagueName = lines[index + 1];
                    matchTitle = lines[index + 2];
                    selectionLine = lines[index + 3];
                    break;
                  }
                  if (/\s+v(?:s)?\s+/i.test(lines[index + 1]) && /^Soccer\b/i.test(lines[index + 2]) && /@\s*-?\d+(?:\.\d+)?/.test(lines[index + 3])) {
                    leagueName = lines[index];
                    matchTitle = lines[index + 1];
                    marketLine = lines[index + 2];
                    selectionLine = lines[index + 3];
                    break;
                  }
                }
                if (!marketLine || !matchTitle || !selectionLine) return null;
                const marketType = /1\s*X\s*2|Moneyline|Winner/i.test(marketLine)
                  ? 'moneyline'
                  : (/Over\s*\/\s*Under|O\/U|Total/i.test(marketLine) ? 'total' : (/Handicap|HDP/i.test(marketLine) ? 'handicap' : 'other'));
                const parsedSelection = parseSelection(selectionLine, marketType);
                const ticket = extractReceiptReference(value) || ticketReference || null;
                let stakeAmount = null;
                let estimatedWin = null;
                let placedAtText = null;
                for (let index = 0; index < lines.length; index += 1) {
                  const line = lines[index];
                  if (/^Stake:/i.test(line)) stakeAmount = parseMoney(line);
                  if (/^Stake$/i.test(line) && index + 1 < lines.length) stakeAmount = parseMoney(lines[index + 1]);
                  if (/^(?:Est\.\s*Win|To\s+Win):/i.test(line)) estimatedWin = parseMoney(line);
                  if (/^(?:Est\.\s*Win|To\s+Win)$/i.test(line) && index + 1 < lines.length) estimatedWin = parseMoney(lines[index + 1]);
                  if (/\d{1,2}:\d{2}:\d{2}\s*\([A-Z]{2,4}\)/.test(line)) placedAtText = line;
                }
                if (!ticket || !Number.isFinite(parsedSelection.odds) || !stakeAmount || stakeAmount <= 0) return null;
                return {
                  ticketReference: ticket,
                  leagueName,
                  matchTitle,
                  marketType,
                  lineValue: parsedSelection.lineValue,
                  selectionName: parsedSelection.selectionName,
                  odds: parsedSelection.odds,
                  stakeAmount,
                  estimatedWin,
                  placedAtText
                };
              };
              const result = async (verified, value) => {
                let receiptOkButton = null;
                if (verified) {
                  await confirmBetIfPrompted();
                  receiptOkButton = await receiptOkButtonPoint();
                  if (!receiptOkButton) await returnToFootballPage();
                }
                return JSON.stringify({
                  placed: true,
                  historyVerified: verified,
                  ticketReference: extractReceiptReference(value) || ticketReference || null,
                  message: verified ? 'crown_history_verified' : 'crown_history_unverified',
                  record: verified ? parseVerifiedBetRecord(value) : null,
                  receiptOkButton: receiptOkButton
                });
              };

              await sleep(600);
              await confirmBetIfPrompted();
              const initial = rawText() || text();
              if (historyVerified(initial)) return await result(true, initial);

              const historyButton = byId('header_todaywagers') || byId('menu_todaywagers');
              if (historyButton) {
                historyButton.scrollIntoView({ block: 'center', inline: 'center' });
                clickElement(historyButton);
                await sleep(1800);
              }

              const deadline = Date.now() + 15000;
              while (Date.now() <= deadline) {
                await confirmBetIfPrompted();
                const current = rawText() || text();
                if (historyVerified(current)) return await result(true, current);
                await sleep(500);
              }
              return await result(false, rawText() || text());
            })()
        """.trimIndent()
    }

    private fun JsonNode.textOrNull(): String? {
        return takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    }
}
