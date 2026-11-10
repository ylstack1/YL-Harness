// Only bundled code runs here. Workspace content crosses the boundary as JSON data.
const editorWorker = new Worker('engine.js');
editorWorker.onmessage = event => EditorBridge.reply(JSON.stringify(event.data));
editorWorker.onerror = () => EditorBridge.reply(JSON.stringify({ fatal: 'The local language service stopped. Reopen the editor to retry.' }));
window.editorRequest = request => editorWorker.postMessage(request);
EditorBridge.reply(JSON.stringify({ ready: true }));
