package com.wrbug.polymarketbot.service.autobetting.crown

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.service.autobetting.CrownBetPlacementCommand
import com.wrbug.polymarketbot.service.autobetting.CrownBetPlacementResult
import com.wrbug.polymarketbot.service.autobetting.CrownOpenBetRecord
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerCdpClient
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerLocalApiClient
import java.math.BigDecimal

class CrownBetPlacementService(
    private val apiClient: AdsPowerLocalApiClient,
    private val cdpClient: AdsPowerCdpClient,
    private val historyVerifier: CrownBetHistoryVerifier,
    private val objectMapper: ObjectMapper
) {
    fun placeBet(command: CrownBetPlacementCommand): CrownBetPlacementResult {
        val active = apiClient.checkProfileActive(command.profileId)
        if (!active.opened) {
            return CrownBetPlacementResult(false, false, null, "profile_not_opened")
        }
        val debugPort = active.debugPort?.trim().orEmpty()
        if (debugPort.isBlank()) {
            return CrownBetPlacementResult(false, false, null, "browser_debug_port_missing")
        }
        cdpClient.closeCrownPrintTargets(debugPort)
        val target = cdpClient.readCrownPageTarget(debugPort, command.loginUrl)
            ?: return CrownBetPlacementResult(false, false, null, "crown_page_not_found")
        val initialWsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
        if (initialWsUrl.isBlank()) {
            return CrownBetPlacementResult(false, false, null, "crown_debug_target_missing")
        }
        cdpClient.installCrownPrintGuard(initialWsUrl)
        val activated = cdpClient.activateCrownPageBeforePlacement(debugPort, target, command.loginUrl)
            ?: return CrownBetPlacementResult(false, false, null, "crown_page_activation_failed")
        val wsUrl = activated.wsUrl
        cdpClient.installCrownPrintGuard(wsUrl)
        val snapshot = activated.snapshot
        val session = CrownSessionPageAnalyzer.analyze(snapshot.text, snapshot.title)
        if (!session.loggedIn) {
            if (session.accountStatus == CROWN_NETWORK_UNSTABLE_STATUS) {
                cdpClient.dismissCrownNetworkPrompt(wsUrl)
            }
            return CrownBetPlacementResult(false, false, null, session.accountStatus)
        }

        val argsJson = objectMapper.writeValueAsString(command)
        val resultText = try {
            cdpClient.evaluateCrownPageJson(wsUrl, crownBetExecutionScript(argsJson))
        } finally {
            cdpClient.closeCrownPrintTargets(debugPort)
        } ?: return CrownBetPlacementResult(false, false, null, "crown_execution_timeout")
        val result = runCatching { objectMapper.readTree(resultText) }.getOrNull()
            ?: return CrownBetPlacementResult(false, false, null, "crown_execution_parse_failed")
        val placementResult = result.toCrownBetPlacementResult()
        if (cdpClient.shouldDispatchNativePlaceBetClick(placementResult.message, result) && cdpClient.dispatchNativePlaceBetClick(wsUrl, result)) {
            val nativeResultText = cdpClient.evaluateCrownPageJson(
                wsUrl,
                historyVerifier.crownBetHistoryVerificationScript(argsJson, objectMapper.writeValueAsString(""))
            )
                ?: return placementResult
            val nativeResult = runCatching { objectMapper.readTree(nativeResultText) }.getOrNull()
                ?: return placementResult
            cdpClient.dispatchNativeReceiptOkClick(wsUrl, nativeResult)
            if (nativeResult.path("historyVerified").asBoolean(false)) {
                return nativeResult.toCrownBetPlacementResult()
            }
        }
        cdpClient.dispatchNativeReceiptOkClick(wsUrl, result)
        return placementResult
    }

    private fun crownBetExecutionScript(argsJson: String): String {
        return """
            (async () => {
              const args = $argsJson;
              const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
              const finish = (payload) => JSON.stringify(payload);
              const accessibleWindows = () => {
                const windows = [];
                const seen = new Set();
                const visit = (win) => {
                  if (!win || seen.has(win)) return;
                  seen.add(win);
                  let doc = null;
                  try {
                    doc = win.document;
                  } catch (_) {
                    return;
                  }
                  if (!doc) return;
                  windows.push(win);
                  for (const frame of Array.from(win.frames || [])) {
                    try {
                      visit(frame);
                    } catch (_) {
                      // Ignore cross-origin frames.
                    }
                  }
                };
                visit(window);
                return windows;
              };
              const findInDocuments = (reader) => {
                for (const win of accessibleWindows()) {
                  try {
                    const value = reader(win.document, win);
                    if (value) return value;
                  } catch (_) {
                    // Ignore transient frame access errors.
                  }
                }
                return null;
              };
              const allInDocuments = (reader) => {
                const values = [];
                for (const win of accessibleWindows()) {
                  try {
                    values.push(...reader(win.document, win).filter(Boolean));
                  } catch (_) {
                    // Ignore transient frame access errors.
                  }
                }
                return values;
              };
              const findElementById = (id) => findInDocuments((doc) => doc.getElementById(id));
              const findSelector = (selector) => findInDocuments((doc) => doc.querySelector(selector));
              const findAllSelector = (selector) => allInDocuments((doc) => Array.from(doc.querySelectorAll(selector)));
              const ownerWindow = (element) => element?.ownerDocument?.defaultView || window;
              const normalizeLine = (value) => String(value || '')
                .replace(/\s+/g, '')
                .replace(/^\+/, '')
                .replace(/^-/, '');
              const currentText = () => allInDocuments((doc) => [doc.body ? doc.body.innerText : ''])
                .filter(Boolean)
                .join('\n');
              const currentRawText = () => allInDocuments((doc) => [doc.body ? doc.body.textContent : ''])
                .filter(Boolean)
                .join('\n');
              const disableNativePrint = () => {
                const guardWindow = (win) => {
                  const noop = () => {};
                  const fakeBody = { innerHTML: '', innerText: '', textContent: '', appendChild: noop };
                  const fakeDocument = {
                    body: fakeBody,
                    documentElement: fakeBody,
                    write: noop,
                    writeln: noop,
                    close: noop,
                    open: () => fakeDocument,
                    createElement: () => fakeBody
                  };
                  const fakePopup = {
                    print: noop,
                    close: noop,
                    focus: noop,
                    blur: noop,
                    closed: false,
                    document: fakeDocument,
                    location: { href: 'about:blank' },
                    addEventListener: noop,
                    removeEventListener: noop
                  };
                  try {
                    Object.defineProperty(win, 'print', {
                      configurable: true,
                      writable: true,
                      value: noop
                    });
                  } catch (_) {
                    win.print = noop;
                  }
                  try {
                    if (!win.__autoBetOriginalOpen && typeof win.open === 'function') {
                      win.__autoBetOriginalOpen = win.open.bind(win);
                    }
                    const openWindow = win.__autoBetOriginalOpen;
                    if (typeof openWindow === 'function') {
                      Object.defineProperty(win, 'open', {
                        configurable: true,
                        writable: true,
                        value: (...openArgs) => {
                          const targetUrl = String(openArgs[0] || '');
                          const targetName = String(openArgs[1] || '');
                          if (!targetUrl || /print|receipt|statement|wager|welcome/i.test(targetUrl) || /print|receipt|statement|wager|welcome/i.test(targetName)) {
                            return fakePopup;
                          }
                          const popup = openWindow(...openArgs);
                          try {
                            if (popup) {
                              guardWindow(popup);
                              setTimeout(() => guardWindow(popup), 50);
                              setTimeout(() => guardWindow(popup), 250);
                            }
                          } catch (_) {
                            // Ignore popup access failures.
                          }
                          return popup;
                        }
                      });
                    }
                  } catch (_) {
                    // Ignore window.open guard failures.
                  }
                  try {
                    if (win.document && !win.document.__autoBetOriginalExecCommand && typeof win.document.execCommand === 'function') {
                      win.document.__autoBetOriginalExecCommand = win.document.execCommand.bind(win.document);
                    }
                    const execCommand = win.document?.__autoBetOriginalExecCommand;
                    if (typeof execCommand === 'function') {
                      win.document.execCommand = (...execArgs) => {
                        const command = String(execArgs[0] || '').toLowerCase();
                        if (command === 'print') return false;
                        return execCommand(...execArgs);
                      };
                    }
                  } catch (_) {
                    // Ignore document command guard failures.
                  }
                  win.onbeforeprint = null;
                  win.onafterprint = null;
                  if (win.document) {
                    win.document.onbeforeprint = null;
                    win.document.onafterprint = null;
                  }
                };
                for (const win of accessibleWindows()) {
                  try {
                    guardWindow(win);
                  } catch (_) {
                    // Ignore cross-frame print guard failures.
                  }
                }
                try {
                  if (!window.__autoBetPrintGuardTimer) {
                    window.__autoBetPrintGuardTimer = setInterval(() => {
                      try {
                        disableNativePrint();
                      } catch (_) {
                        // Ignore repeated guard failures.
                      }
                    }, 500);
                  }
                } catch (_) {
                  // Ignore print guard timer failures.
                }
              };
              const readWagerCount = () => {
                const countText = [
                  findElementById('wager_count')?.innerText,
                  findElementById('pc_wager_count')?.innerText
                ].filter(Boolean).join(' ');
                const match = String(countText || '').match(/\d+/);
                return match ? Number(match[0]) : 0;
              };
              const extractReceiptReference = (text) => {
                const ticketMatch = String(text || '').match(/Ticket No:\s*([A-Za-z0-9-]+)/i);
                if (ticketMatch) return ticketMatch[1];
                const fantasyMatch = String(text || '').match(/\b(?:OU|HDP|FS|FT|BK)[A-Z0-9]*\d{6,}\b/i);
                return fantasyMatch ? fantasyMatch[0] : null;
              };
              const receiptVerified = (text) => {
                const value = String(text || '');
                if (/Rejected|Failed to Place|not placed|incorrect|maximum|minimum/i.test(value)) return false;
                if (/successfully placed|YOUR BETS HAVE BEEN SUCCESSFULLY PLACED|BET RECEIPT|Ticket No:/i.test(value)) {
                  return true;
                }
                return /Confirmed/i.test(value) && Boolean(extractReceiptReference(value));
              };
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
                const tokens = expectedMatchTokens();
                if (selection === 'home' || selection === '主队') return tokens[0] || null;
                if (selection === 'away' || selection === '客队') return tokens[1] || null;
                return selectionText || null;
              };
              const stakeText = () => Number(args.stakeAmount).toFixed(2);
              const expectedMatchTokens = () => String(args.matchTitle || '')
                .split(/\s+vs\s+|\s+v\s+/i)
                .map((part) => part.trim().toLowerCase())
                .filter((part) => part.length >= 2);
              const openBetMatchesExpectedMatch = (text) => {
                const tokens = expectedMatchTokens();
                if (tokens.length < 2) return true;
                const value = String(text || '').toLowerCase();
                return tokens.every((token) => value.includes(token));
              };
              const openBetVerified = (text) => {
                const value = String(text || '');
                if (!/OPEN BETS|Confirmed|Statement/i.test(value)) return false;
                if (!openBetMatchesExpectedMatch(value)) return false;
                const expectedStake = stakeText();
                if (!value.includes('Stake: ' + expectedStake) && !value.includes('Stake:' + expectedStake)) {
                  return false;
                }
                if (args.lineValue && !normalizeLine(value).includes(normalizeLine(args.lineValue))) {
                  return false;
                }
                const side = expectedOpenBetSide();
                if (side && !(new RegExp('\\b' + side + '\\b', 'i')).test(value)) {
                  return false;
                }
                const selectionText = expectedOpenBetSelection();
                if (selectionText && !value.toLowerCase().includes(selectionText.toLowerCase())) {
                  return false;
                }
                return Boolean(extractReceiptReference(value)) || /Confirmed/i.test(value);
              };
              const parseMoney = (value) => {
                const match = String(value || '').replace(/,/g, '').match(/\d+(?:\.\d+)?/);
                return match ? Number(match[0]) : null;
              };
              const parseVerifiedBetSelection = (selectionLine, marketType) => {
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
              const parseVerifiedBetRecord = (text, currentOdds = null) => {
                const lines = String(text || '')
                  .split('\n')
                  .map((line) => line.trim())
                  .filter(Boolean);
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
                const parsedSelection = parseVerifiedBetSelection(selectionLine, marketType);
                const ticketReference = extractReceiptReference(text);
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
                const odds = Number.isFinite(parsedSelection.odds) ? parsedSelection.odds : currentOdds;
                if (!ticketReference || !Number.isFinite(odds) || !stakeAmount || stakeAmount <= 0) return null;
                return {
                  ticketReference,
                  leagueName,
                  matchTitle,
                  marketType,
                  lineValue: parsedSelection.lineValue,
                  selectionName: parsedSelection.selectionName,
                  odds,
                  stakeAmount,
                  estimatedWin,
                  placedAtText
                };
              };
              const verifiedOpenBetPayload = (text, currentOdds = null) => ({
                placed: true,
                historyVerified: true,
                ticketReference: extractReceiptReference(text),
                message: 'crown_history_verified',
                currentOdds,
                record: parseVerifiedBetRecord(text, currentOdds)
              });
                const clickElement = (element) => {
                  if (!element) return;
                  const fireClick = () => {
                    element.scrollIntoView({ block: 'center', inline: 'center' });
                    const view = ownerWindow(element);
                    const MouseEventCtor = view.MouseEvent || window.MouseEvent;
                    for (const type of ['mouseover', 'mousedown', 'mouseup', 'click']) {
                      element.dispatchEvent(new MouseEventCtor(type, { bubbles: true, cancelable: true, view }));
                    }
                  };
                  setTimeout(fireClick, 0);
                };
                const isVisible = (element) => Boolean(element && (
                  (() => {
                    const view = ownerWindow(element);
                    const style = view.getComputedStyle(element);
                    return style.display !== 'none'
                      && style.visibility !== 'hidden'
                      && style.visibility !== 'collapse'
                      && style.opacity !== '0'
                      && (element.offsetWidth || element.offsetHeight || element.getClientRects().length);
                  })()
                ));
                const stakeInputSelectors = () => [
                  'input#bet_gold_pc',
                  'input#bet_gold',
                  'input[placeholder="Enter Stake"]'
                ];
                const visibleStakeInputs = (...selectors) => {
                  const seen = new Set();
                  return selectors
                    .flatMap((selector) => findAllSelector(selector))
                    .filter((input) => {
                      if (!input || seen.has(input) || !isVisible(input)) return false;
                      seen.add(input);
                      return true;
                    });
                };
                const visibleStakeInput = (...selectors) => visibleStakeInputs(...selectors)[0] || null;
                const parseStakeNumber = (value) => {
                  const parsed = Number(String(value || '').replace(/,/g, '').trim());
                  return Number.isFinite(parsed) ? parsed : null;
                };
                const readStakeNumber = (input) => parseStakeNumber(input?.value) || 0;
                const textMatchesExpectedSlip = (text) => {
                  const value = String(text || '');
                  const lower = value.toLowerCase();
                  const tokens = expectedMatchTokens();
                  if (tokens.length >= 2 && !tokens.every((token) => lower.includes(token))) return false;
                  if (args.lineValue && !normalizeLine(value).includes(normalizeLine(args.lineValue))) return false;
                  const side = expectedOpenBetSide();
                  if (side && !(new RegExp('\\b' + side + '\\b', 'i')).test(value)) return false;
                  const selectionText = expectedOpenBetSelection();
                  if (selectionText && !lower.includes(selectionText.toLowerCase())) return false;
                  return true;
                };
                const nearestStakeContainer = (input) => {
                  let current = input?.parentElement || null;
                  let best = input;
                  for (let depth = 0; current && depth < 10; depth += 1) {
                    const inputs = Array.from(current.querySelectorAll('input')).filter((candidate) => isVisible(candidate));
                    const text = String(current.innerText || current.textContent || '').trim();
                    if (inputs.includes(input) && text) {
                      best = current;
                      if (inputs.length === 1 && /Soccer|Handicap|Over\s*\/\s*Under|HDP|O\/U|@/i.test(text)) {
                        return current;
                      }
                    }
                    if (inputs.length > 1) return best;
                    current = current.parentElement;
                  }
                  return best;
                };
                const stakeInputCandidates = () => visibleStakeInputs(...stakeInputSelectors())
                  .map((input) => {
                    const container = nearestStakeContainer(input);
                    const text = String(container?.innerText || container?.textContent || '');
                    return {
                      input,
                      container,
                      text,
                      matchesExpected: textMatchesExpectedSlip(text),
                      selectionLike: /Soccer|Handicap|Over\s*\/\s*Under|HDP|O\/U|@/i.test(text)
                        && !/^\s*Singles\b/i.test(text)
                    };
                  });
                const findMatchingBetSlipStakeInput = () => {
                  const candidates = stakeInputCandidates();
                  const exact = candidates.find((candidate) => candidate.matchesExpected);
                  if (exact) return exact.input;
                  return candidates.length === 1 ? candidates[0].input : null;
                };
                const betSlipCloseControl = (container) => {
                  if (!container) return null;
                  const containerRect = container.getBoundingClientRect();
                  const controls = Array.from(container.querySelectorAll('button,a,[role="button"],div,span'))
                    .filter((element) => element !== container && isVisible(element));
                  return controls.find((element) => {
                    const text = String(element.innerText || element.textContent || element.value || '').trim();
                    const attrs = [
                      element.id,
                      element.className,
                      element.getAttribute?.('aria-label'),
                      element.getAttribute?.('title')
                    ].filter(Boolean).join(' ');
                    const rect = element.getBoundingClientRect();
                    const nearRightEdge = rect.left >= containerRect.left + (containerRect.width * 0.65);
                    const singleCharCode = text.length === 1 ? text.charCodeAt(0) : null;
                    const closeGlyph = singleCharCode === 215 || singleCharCode === 10005 || singleCharCode === 10006;
                    return /^x$/i.test(text)
                      || closeGlyph
                      || /delete|remove|close|trash|clear|del/i.test(attrs)
                      || (nearRightEdge && (/x/i.test(text) || closeGlyph));
                  }) || null;
                };
                const pruneUnexpectedBetSlipSelections = async () => {
                  const candidates = stakeInputCandidates();
                  if (!candidates.some((candidate) => candidate.matchesExpected)) return false;
                  let removed = false;
                  for (const candidate of candidates) {
                    if (candidate.matchesExpected || !candidate.selectionLike) continue;
                    const closeControl = betSlipCloseControl(candidate.container);
                    if (!closeControl) continue;
                    clickElement(closeControl);
                    removed = true;
                    await sleep(250);
                  }
                  if (removed) await sleep(700);
                  return removed;
                };
                const closedBettingPattern = /currently\s+closed\s+for\s+betting|selection\s+is\s+closed|closed\s+for\s+betting|market\s+closed|not\s+available|suspended/i;
                const expectedBetSlipContainer = () => {
                  const candidates = stakeInputCandidates();
                  const exact = candidates.find((candidate) => candidate.matchesExpected);
                  if (exact?.container) return exact.container;
                  return findAllSelector('div,section,li')
                    .filter((element) => isVisible(element))
                    .map((element) => ({
                      element,
                      text: String(element.innerText || element.textContent || '')
                    }))
                    .find((candidate) => textMatchesExpectedSlip(candidate.text)
                      && (/Soccer|Handicap|Over\s*\/\s*Under|HDP|O\/U|@/i.test(candidate.text)
                        || closedBettingPattern.test(candidate.text)))?.element || null;
                };
                const closeExpectedBetSlipSelection = async () => {
                  const container = expectedBetSlipContainer();
                  const closeControl = betSlipCloseControl(container);
                  if (!closeControl) return false;
                  clickElement(closeControl);
                  await sleep(700);
                  return true;
                };
                const closeClosedBetSlipSelection = async () => {
                  const text = currentText();
                  const rawText = currentRawText();
                  if (!closedBettingPattern.test(text) && !closedBettingPattern.test(rawText)) return false;
                  return closeExpectedBetSlipSelection();
                };
                const failAfterSelection = async (payload) => {
                  await closeExpectedBetSlipSelection();
                  return finish(payload);
                };
                const buttonDisabled = (button) => Boolean(button?.disabled)
                  || String(button?.getAttribute?.('aria-disabled') || '').toLowerCase() === 'true'
                  || /\bdisabled\b/i.test(String(button?.className || ''));
                const buttonTextMatchesPlaceBet = (button) => {
                  const text = String(button?.innerText || button?.textContent || button?.value || '').trim();
                  return /PLACE\s*BET/i.test(text)
                    && !/PLACE\s*BET\s*0(?:\.00)?\s*RMB/i.test(text)
                    && !/Add\s+to\s+Bet\s+Slip/i.test(text);
                };
                const mixedPlaceBetActionBar = (button) => {
                  const text = String(button?.innerText || button?.textContent || button?.value || '').trim();
                  return /Add\s+to\s+Bet\s+Slip/i.test(text)
                    && /PLACE\s*BET/i.test(text)
                    && !/PLACE\s*BET\s*0(?:\.00)?\s*RMB/i.test(text);
                };
                const findPlaceBetButton = () => {
                  const fixedButton = findElementById('order_bet');
                  if (fixedButton && !buttonDisabled(fixedButton) && isVisible(fixedButton)) {
                    const fixedDescendant = Array.from(fixedButton.querySelectorAll('button,input[type="button"],input[type="submit"],[role="button"],a,div,span'))
                      .find((button) => isVisible(button) && !buttonDisabled(button) && buttonTextMatchesPlaceBet(button));
                    if (fixedDescendant) return fixedDescendant;
                    const fixedPlaceButton = buttonTextMatchesPlaceBet(fixedButton) || mixedPlaceBetActionBar(fixedButton)
                      ? fixedButton
                      : null;
                    if (fixedPlaceButton) return fixedPlaceButton;
                  }
                  return findAllSelector('button,input[type="button"],input[type="submit"],[role="button"],a,div,span')
                    .find((button) => isVisible(button) && !buttonDisabled(button) && buttonTextMatchesPlaceBet(button)) || null;
                };
                const placeClickAccepted = () => {
                  const text = currentText();
                  const rawText = currentRawText();
                  if (openBetVerified(rawText || text) || receiptVerified(text)) return true;
                  if (/Rejected|Failed to Place|not placed|incorrect|maximum|minimum/i.test(text)) return true;
                  if (isVisible(findElementById('alert_confirm')) || isVisible(findElementById('C_alert_confirm'))) return true;
                  return false;
                };
                const placeBetClickPoint = (element) => {
                  if (!element) return null;
                  const rect = element.getBoundingClientRect();
                  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
                  const text = String(element.innerText || element.textContent || element.value || '').trim();
                  const x = /Add\s+to\s+Bet\s+Slip/i.test(text) && /PLACE\s*BET/i.test(text)
                    ? rect.left + rect.width * 0.75
                    : rect.left + rect.width / 2;
                  return { x, y: rect.top + rect.height / 2 };
                };
                const absoluteCenter = (element) => {
                  if (!element) return null;
                  try { element.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
                  let rect = element.getBoundingClientRect();
                  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
                  const point = placeBetClickPoint(element);
                  if (!point) return null;
                  let left = point.x;
                  let top = point.y;
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
                const trustedLikeClickElement = (element) => {
                  if (!element) return false;
                  const view = ownerWindow(element);
                  element.scrollIntoView({ block: 'center', inline: 'center' });
                  const point = placeBetClickPoint(element);
                  if (!point) return false;
                  const x = point.x;
                  const y = point.y;
                  const target = element.ownerDocument?.elementFromPoint?.(x, y) || element;
                  const MouseEventCtor = view.MouseEvent || window.MouseEvent;
                  const PointerEventCtor = view.PointerEvent || MouseEventCtor;
                  try { element.focus?.(); } catch (_) {}
                  for (const type of ['pointerover', 'pointermove', 'pointerdown', 'pointerup']) {
                    try {
                      target.dispatchEvent(new PointerEventCtor(type, {
                        bubbles: true,
                        cancelable: true,
                        view,
                        clientX: x,
                        clientY: y,
                        pointerType: 'mouse',
                        button: 0,
                        buttons: type === 'pointerdown' ? 1 : 0
                      }));
                    } catch (_) {}
                  }
                  for (const type of ['mouseover', 'mousemove', 'mousedown', 'mouseup', 'click']) {
                    target.dispatchEvent(new MouseEventCtor(type, {
                      bubbles: true,
                      cancelable: true,
                      view,
                      clientX: x,
                      clientY: y,
                      button: 0,
                      buttons: type === 'mousedown' ? 1 : 0
                    }));
                  }
                  try { target.click?.(); } catch (_) {}
                  if (target !== element) {
                    try { element.click?.(); } catch (_) {}
                  }
                  return true;
                };
                const clickPlaceBetButton = async (initialButton) => {
                  for (let attempt = 0; attempt < 3; attempt += 1) {
                    const button = attempt === 0 && initialButton && isVisible(initialButton)
                      ? initialButton
                      : findPlaceBetButton();
                    if (!button) return false;
                    trustedLikeClickElement(button);
                    await sleep(650);
                    await confirmBetIfPrompted();
                    if (placeClickAccepted()) return true;
                  }
                  return placeClickAccepted();
                };
                const parseCrownOddsText = (value) => {
                  const matches = String(value || '').replace(/,/g, '').match(/-?\d+(?:\.\d+)?/g);
                  if (!matches || matches.length === 0) return null;
                  const numbers = matches.map(Number).filter((number) => Number.isFinite(number));
                  return numbers.length > 0 ? numbers[numbers.length - 1] : null;
                };
                const readBetElementOdds = (element) => {
                  const oddsNodes = Array.from(element.querySelectorAll('.text_odds, [data-ior], [ior], [data-odds], [odds]'));
                  for (const node of oddsNodes) {
                    if (!isVisible(node)) continue;
                    for (const attr of ['data-ior', 'ior', 'data-odds', 'odds']) {
                      const parsed = parseCrownOddsText(node.getAttribute(attr));
                      if (parsed !== null) return parsed;
                    }
                    const parsed = parseCrownOddsText(node.innerText || node.textContent);
                    if (parsed !== null) return parsed;
                  }
                  for (const attr of ['data-ior', 'ior', 'data-odds', 'odds']) {
                    const parsed = parseCrownOddsText(element.getAttribute(attr));
                    if (parsed !== null) return parsed;
                  }
                  return parseCrownOddsText(element.innerText || element.textContent);
                };
                const confirmBetIfPrompted = async () => {
                  const prompts = [
                    { container: 'alert_confirm', yes: 'yes_btn', checkbox: 'confirm_chk' },
                    { container: 'C_alert_confirm', yes: 'C_yes_btn', checkbox: 'C_confirm_chk' }
                  ];
                  for (let index = 0; index < 20; index += 1) {
                    for (const prompt of prompts) {
                      const container = findElementById(prompt.container);
                      if (!isVisible(container)) continue;
                      const checkbox = findElementById(prompt.checkbox);
                      if (checkbox && !checkbox.checked) {
                        checkbox.checked = true;
                        const EventCtor = ownerWindow(checkbox).Event || window.Event;
                        checkbox.dispatchEvent(new EventCtor('change', { bubbles: true }));
                        await sleep(100);
                      }
                      const yesButton = findElementById(prompt.yes);
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
                const closeSuccessfulReceiptPanel = async () => {
                  const receiptMarker = /YOUR BETS HAVE BEEN SUCCESSFULLY PLACED|successfully placed|BET RECEIPT/i;
                  const retainMarker = /Retain Selection/i;
                  const receiptVisible = () => {
                    const text = currentText();
                    const rawText = currentRawText();
                    return receiptMarker.test(text) || receiptMarker.test(rawText) || retainMarker.test(text);
                  };
                  if (!receiptVisible()) return false;
                  const selectors = [
                    '#order_close',
                    '#btn_close',
                    '#close_btn',
                    '.close',
                    '.btn_close',
                    '.order_close',
                    'button',
                    '[role="button"]',
                    'a',
                    'div'
                  ];
                  for (let attempt = 0; attempt < 8; attempt += 1) {
                    const candidates = selectors.flatMap((selector) => findAllSelector(selector));
                    const okButton = candidates.find((element) => {
                      if (!isVisible(element)) return false;
                      const text = String(element.innerText || element.textContent || element.value || '').trim();
                      if (text === 'OK') return true;
                      const id = String(element.id || '');
                      const className = String(element.className || '');
                      return /order_close|close|ok/i.test(id) || /order_close|close|ok/i.test(className);
                    });
                    if (okButton) {
                      clickElement(okButton);
                      await sleep(500);
                      if (!receiptVisible()) return true;
                    }
                    await sleep(250);
                  }
                  return false;
                };
                const betSlipLimitReached = () => {
                  const text = currentText();
                  const rawText = currentRawText();
                  return /最多\s*10\s*个选项|最多十个选项|maximum\s*10|10\s*selections/i.test(text)
                    || /最多\s*10\s*个选项|最多十个选项|maximum\s*10|10\s*selections/i.test(rawText);
                };
                const fillStakeInput = async (stakeInput, stake, accepted = null) => {
                  const view = ownerWindow(stakeInput);
                  const stakeDocument = stakeInput.ownerDocument || document;
                  const EventCtor = view.Event || window.Event;
                  const KeyboardEventCtor = view.KeyboardEvent || window.KeyboardEvent;
                  const InputEventCtor = view.InputEvent || window.InputEvent || EventCtor;
                  const HTMLInputElementCtor = view.HTMLInputElement || window.HTMLInputElement;
                  const findStakeElementById = (id) => stakeDocument.getElementById(id) || findElementById(id);
                  const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElementCtor.prototype, 'value')?.set;
                  const normalizeStakeValue = (value) => {
                    const parsed = parseStakeNumber(value);
                    return parsed !== null ? String(parsed) : String(value || '').trim();
                  };
                  const expectedStake = normalizeStakeValue(stake);
                  const stakeMatches = () => normalizeStakeValue(stakeInput.value) === expectedStake;
                  const stakeAccepted = async () => {
                    await sleep(250);
                    if (!stakeMatches()) return false;
                    if (!accepted || accepted()) return true;
                    await sleep(500);
                    return stakeMatches() && (!accepted || accepted());
                  };
                  const focusStakeInput = async () => {
                    stakeInput.scrollIntoView({ block: 'center', inline: 'center' });
                    stakeInput.focus();
                    clickElement(stakeInput);
                    await sleep(150);
                  };
                  const setStakeValue = (value) => {
                    if (valueSetter) {
                      valueSetter.call(stakeInput, value);
                    } else {
                      stakeInput.value = value;
                    }
                  };
                  const emitStakeInput = (data = null) => {
                    stakeInput.dispatchEvent(new InputEventCtor('input', {
                      bubbles: true,
                      inputType: data === null ? 'deleteContentBackward' : 'insertText',
                      data
                    }));
                    stakeInput.dispatchEvent(new EventCtor('change', { bubbles: true }));
                  };
                  const applyStakeDirectly = () => {
                    setStakeValue('');
                    emitStakeInput(null);
                    for (const char of stake) {
                      stakeInput.dispatchEvent(new KeyboardEventCtor('keydown', {
                        bubbles: true,
                        cancelable: true,
                        key: char,
                        code: 'Digit' + char
                      }));
                      stakeInput.dispatchEvent(new KeyboardEventCtor('keypress', {
                        bubbles: true,
                        cancelable: true,
                        key: char,
                        code: 'Digit' + char
                      }));
                      setStakeValue(String(stakeInput.value || '') + char);
                      emitStakeInput(char);
                      stakeInput.dispatchEvent(new KeyboardEventCtor('keyup', {
                        bubbles: true,
                        cancelable: true,
                        key: char,
                        code: 'Digit' + char
                      }));
                    }
                    stakeInput.dispatchEvent(new EventCtor('blur', { bubbles: true }));
                    stakeInput.dispatchEvent(new EventCtor('change', { bubbles: true }));
                    return stakeMatches();
                  };
                  const keyboardVisible = () => isVisible(findStakeElementById('num_0'))
                    && isVisible(findStakeElementById('num_done'));
                  await focusStakeInput();
                  if (!keyboardVisible()) {
                    await sleep(500);
                  }
                  if (keyboardVisible()) {
                    for (let index = 0; index < 14; index += 1) {
                      const deleteButton = findStakeElementById('num_x');
                      if (deleteButton) clickElement(deleteButton);
                      await sleep(35);
                    }
                    for (const char of stake) {
                      const numberButton = findStakeElementById('num_' + char);
                      if (!numberButton) return false;
                      clickElement(numberButton);
                      await sleep(80);
                    }
                    return stakeAccepted();
                  }
                  if (applyStakeDirectly()) {
                    if (await stakeAccepted()) return true;
                  }
                  return stakeAccepted();
                };
                const waitFor = async (reader, timeoutMs = 10000, intervalMs = 250) => {
                  const deadline = Date.now() + timeoutMs;
                  while (Date.now() <= deadline) {
                  const value = reader();
                  if (value) return value;
                  await sleep(intervalMs);
                }
                return null;
              };
              const menuCandidateIds = () => {
                const phase = String(args.matchPhase || 'live').toLowerCase();
                if (phase === 'prematch') {
                  return ['today_page', 'old_ft_league', 'ft_league', 'old_ft_today_league', 'ft_today_league', 'today'];
                }
                return ['old_ft_live_league', 'ft_live_league', 'live_page'];
              };
              const openTargetSoccerPage = async () => {
                const existing = findElementById(args.betElementId);
                if (existing) return existing;
                const candidates = menuCandidateIds();
                for (const id of candidates) {
                  const element = findElementById(id);
                  if (element) {
                    clickElement(element);
                    await sleep(1200);
                    const found = findElementById(args.betElementId);
                    if (found) return found;
                  }
                  }
                  return waitFor(() => findElementById(args.betElementId), 8000, 300);
                };
                const returnToFootballPage = async () => {
                  const candidates = menuCandidateIds();
                  for (const id of candidates) {
                    const element = findElementById(id);
                    if (element && isVisible(element)) {
                      clickElement(element);
                      await sleep(1000);
                      return true;
                    }
                  }
                  return false;
                };
                disableNativePrint();
                const existingOpenBetText = currentRawText() || currentText();
                if (openBetVerified(existingOpenBetText)) {
                  await returnToFootballPage();
                  return finish(verifiedOpenBetPayload(existingOpenBetText));
                }
                const betElement = await openTargetSoccerPage();
                if (!betElement) {
                  return finish({ placed: false, historyVerified: false, message: 'crown_market_not_found' });
              }
              if (betElement.classList.contains('lock')) {
                return finish({ placed: false, historyVerified: false, message: 'crown_market_locked' });
              }
              const lineText = Array.from(betElement.querySelectorAll('.text_ballhead'))
                .map((element) => element.innerText)
                .join(' ');
              if (args.lineValue && normalizeLine(lineText) !== normalizeLine(args.lineValue)) {
                return finish({
                  placed: false,
                  historyVerified: false,
                  message: 'crown_line_mismatch',
                  currentLine: lineText
                });
              }
              const currentOdds = readBetElementOdds(betElement);
                if (!Number.isFinite(currentOdds)) {
                  return finish({ placed: false, historyVerified: false, message: 'crown_odds_missing' });
                }
                if (currentOdds < Number(args.targetOdds)) {
                  return finish({
                    placed: false,
                    historyVerified: false,
                    message: 'target_odds_below_minimum',
                  currentOdds
                });
              }
  
                const beforeWagerCount = readWagerCount();
                clickElement(betElement);
                await sleep(500);
                if (betSlipLimitReached()) {
                  return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_betslip_full', currentOdds });
                }
                await waitFor(() => findMatchingBetSlipStakeInput(), 10000, 250);
                await pruneUnexpectedBetSlipSelections();
                const stake = String(Number(args.stakeAmount));
                const stakeSettleBeforePlaceBetMs = 10000;
                const stakeInput = await waitFor(() => findMatchingBetSlipStakeInput(), 10000, 250);
                if (!stakeInput) {
                  if (betSlipLimitReached()) {
                    return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_betslip_full', currentOdds });
                  }
                  if (await closeClosedBetSlipSelection()) {
                    return finish({ placed: false, historyVerified: false, message: 'crown_selection_closed', currentOdds });
                  }
                  return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_stake_input_missing', currentOdds });
                }
                const stakeAcceptedBySlip = () => {
                  const expectedStakeAmount = Number(stake);
                  const candidates = stakeInputCandidates();
                  const exactStakeCandidates = candidates.filter((candidate) =>
                    readStakeNumber(candidate.input) === expectedStakeAmount
                  );
                  const filledExpectedSelection = exactStakeCandidates.find((candidate) => candidate.matchesExpected)
                    || (exactStakeCandidates.length === 1 ? exactStakeCandidates[0] : null);
                  if (!filledExpectedSelection) return false;
                  const unexpectedFilledSelection = candidates.some((candidate) =>
                    candidate !== filledExpectedSelection && candidate.selectionLike && readStakeNumber(candidate.input) > 0
                  );
                  if (unexpectedFilledSelection) return false;
                  const button = findPlaceBetButton();
                  if (button) return true;
                  const pageText = currentText();
                  return /PLACE\s*BET/i.test(pageText)
                    && !/PLACE\s*BET\s*0(?:\.00)?\s*RMB/i.test(pageText);
                };
                const stakeFilled = await fillStakeInput(stakeInput, stake, stakeAcceptedBySlip);
                if (!stakeFilled) {
                  return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_stake_input_not_applied', currentOdds });
                }
                await sleep(stakeSettleBeforePlaceBetMs);
  
                let orderButton = await waitFor(() => {
                  return findPlaceBetButton();
                }, 4000, 250);
                if (!orderButton) {
                  const pageText = currentText();
                  const rawPageText = currentRawText();
                  if (openBetVerified(rawPageText || pageText)) {
                    return finish(verifiedOpenBetPayload(rawPageText || pageText, currentOdds));
                  }
                  if (/minimum stake|min(?:imum)?\\.? stake/i.test(pageText)) {
                    return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_stake_below_minimum', currentOdds });
                  }
                  if (/maximum stake|max(?:imum)?\\.? stake/i.test(pageText)) {
                    return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_stake_above_maximum', currentOdds });
                  }
                  if (betSlipLimitReached()) {
                    return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_betslip_full', currentOdds });
                  }
                  if (await closeClosedBetSlipSelection()) {
                    return finish({ placed: false, historyVerified: false, message: 'crown_selection_closed', currentOdds });
                  }
                  return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_place_button_disabled', currentOdds });
                }
                disableNativePrint();
                const finalPlaceButton = findPlaceBetButton() || orderButton;
                const nativePlaceButtonPoint = absoluteCenter(finalPlaceButton);
                if (nativePlaceButtonPoint) {
                  return finish({
                    placed: false,
                    historyVerified: false,
                    message: 'crown_place_button_native_click_required',
                    currentOdds,
                    placeButton: nativePlaceButtonPoint
                  });
                }
                return await failAfterSelection({
                  placed: false,
                  historyVerified: false,
                  message: 'crown_place_button_click_failed',
                  currentOdds,
                  placeButton: nativePlaceButtonPoint
                });
              disableNativePrint();
              await confirmBetIfPrompted();
              disableNativePrint();

              const receiptText = await waitFor(() => {
                disableNativePrint();
                const confirmVisible = isVisible(findElementById('alert_confirm'))
                  || isVisible(findElementById('C_alert_confirm'));
                if (confirmVisible) {
                  confirmBetIfPrompted();
                }
                const text = currentText();
                const rawText = currentRawText();
                if (openBetVerified(rawText || text)) return rawText || text;
                if (receiptVerified(text)) return text;
                if (/Rejected|Failed to Place|not placed|incorrect|maximum|minimum/i.test(text)) return text;
                return null;
              }, 18000, 500);
              if (receiptText && openBetVerified(receiptText)) {
                await closeSuccessfulReceiptPanel();
                await returnToFootballPage();
                return finish(verifiedOpenBetPayload(receiptText, currentOdds));
              }
              if (!receiptText || !receiptVerified(receiptText)) {
                return await failAfterSelection({
                  placed: false,
                  historyVerified: false,
                  message: 'crown_bet_not_confirmed',
                  currentOdds,
                  pageText: String(receiptText || currentText()).slice(0, 500)
                });
              }
              const ticketReference = extractReceiptReference(receiptText);
              if (ticketReference && receiptVerified(receiptText)) {
                await closeSuccessfulReceiptPanel();
                await returnToFootballPage();
                return finish({
                  placed: true,
                  historyVerified: true,
                  ticketReference,
                  message: 'crown_receipt_verified',
                  currentOdds,
                  record: parseVerifiedBetRecord(receiptText, currentOdds)
                });
              }

              const historyButton = findElementById('header_todaywagers')
                || findElementById('menu_todaywagers');
              if (historyButton) {
                clickElement(historyButton);
                await sleep(1800);
              }
              const historyText = await waitFor(() => {
                const text = currentText();
                const rawText = currentRawText();
                const currentWagerCount = readWagerCount();
                if (ticketReference && (text.includes(ticketReference) || rawText.includes(ticketReference))) {
                  return rawText || text;
                }
                if (currentWagerCount > beforeWagerCount && /successfully placed|BET RECEIPT/i.test(rawText || text)) {
                  return rawText || text;
                }
                if (openBetVerified(rawText || text)) {
                  return rawText || text;
                }
                if (/Confirmed|Open Bets|OPEN BETS|My Bets/i.test(text)
                  && text.includes(stake)
                  && (text.includes('Al ') || /HDP|O\/U/i.test(text))) {
                  return text;
                }
                return null;
              }, 12000, 500);
              if (!historyText) {
                await returnToFootballPage();
                return finish({
                  placed: true,
                  historyVerified: false,
                  ticketReference,
                  message: 'crown_history_unverified',
                  currentOdds
                });
              }
              const verifiedTicketReference = ticketReference || extractReceiptReference(historyText);
              await closeSuccessfulReceiptPanel();
              await returnToFootballPage();
              return finish({
                placed: true,
                historyVerified: true,
                ticketReference: verifiedTicketReference,
                message: 'crown_history_verified',
                currentOdds,
                record: parseVerifiedBetRecord(historyText, currentOdds)
              });
            })()
        """.trimIndent()
    }

    private fun JsonNode.textOrNull(): String? {
        return takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    }

    private fun com.fasterxml.jackson.databind.JsonNode.decimalOrNull(): BigDecimal? {
        if (isMissingNode || isNull) return null
        return runCatching { BigDecimal(asText().replace(",", "").trim()) }.getOrNull()
    }

    private fun com.fasterxml.jackson.databind.JsonNode.toCrownBetPlacementResult(): CrownBetPlacementResult {
        return CrownBetPlacementResult(
            placed = path("placed").asBoolean(false),
            historyVerified = path("historyVerified").asBoolean(false),
            ticketReference = path("ticketReference").textOrNull(),
            message = path("message").textOrNull() ?: "crown_bet_failed",
            currentOdds = path("currentOdds").decimalOrNull(),
            verifiedRecord = path("record").crownOpenBetRecordOrNull()
        )
    }

    private fun com.fasterxml.jackson.databind.JsonNode.crownOpenBetRecordOrNull(): CrownOpenBetRecord? {
        if (isMissingNode || isNull || !isObject) return null
        val odds = path("odds").decimalOrNull() ?: return null
        val stakeAmount = path("stakeAmount").decimalOrNull() ?: return null
        val ticketReference = path("ticketReference").textOrNull()
        if (ticketReference.isNullOrBlank() || stakeAmount <= BigDecimal.ZERO) return null
        return CrownOpenBetRecord(
            ticketReference = ticketReference,
            leagueName = path("leagueName").textOrNull().orEmpty(),
            matchTitle = path("matchTitle").textOrNull().orEmpty(),
            marketType = path("marketType").textOrNull().orEmpty(),
            lineValue = path("lineValue").textOrNull(),
            selectionName = path("selectionName").textOrNull().orEmpty(),
            odds = odds,
            stakeAmount = stakeAmount,
            estimatedWin = path("estimatedWin").decimalOrNull(),
            placedAtText = path("placedAtText").textOrNull()
        )
    }
}
