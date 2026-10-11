package org.astrasec.launcher.apkupload

internal object UploadPage {
    val HTML = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>Astra · 安装应用</title>
          <style>
            :root { color-scheme: dark; font-family: system-ui, -apple-system, sans-serif; }
            * { box-sizing: border-box; }
            body { margin: 0; padding: 40px 20px; background: #101727; color: #eef2fc; }
            main { max-width: 540px; margin: 6vh auto; padding: 30px; border: 1px solid #34425c; border-radius: 24px; background: #19243a; }
            .brand { color: #9dbbff; font-size: 14px; letter-spacing: .08em; }
            h1 { font-size: 28px; margin: 14px 0; }
            p { color: #c2ccdf; line-height: 1.65; }
            label { display: block; margin: 24px 0 10px; font-weight: 600; }
            input, button { width: 100%; padding: 15px; border-radius: 12px; font: inherit; }
            input { color: #eef2fc; background: #101a2d; border: 1px solid #52627d; }
            input[type=file] { font-size: 14px; }
            button { margin-top: 26px; border: 0; background: #b7ceff; color: #101727; font-weight: 700; cursor: pointer; }
            button:disabled, input:disabled { opacity: .5; cursor: wait; }
            :focus-visible { outline: 3px solid #7be5dd; outline-offset: 3px; }
            progress { margin-top: 24px; width: 100%; accent-color: #9dbbff; }
            #status { min-height: 3em; white-space: pre-wrap; overflow-wrap: anywhere; }
            .hint { font-size: 13px; color: #98a9c5; }
          </style>
        </head>
        <body>
          <main>
            <div class="brand">ASTRA LAUNCHER</div>
            <h1>把应用发送到电视</h1>
            <p>选择 APK，输入电视上显示的接收码。传输完成后，请在电视上确认安装。</p>
            <label for="file">APK 文件</label>
            <input id="file" type="file" accept=".apk,application/vnd.android.package-archive">
            <label for="pin">接收码</label>
            <input id="pin" type="text" inputmode="numeric" autocomplete="off" maxlength="64" placeholder="查看电视上的接收码">
            <button id="send" type="button">发送 APK</button>
            <progress id="progress" value="0" max="100" hidden></progress>
            <p id="status" role="status" aria-live="polite">尚未选择文件</p>
            <p class="hint">单个 APK 最大 256 MB。手机或电脑须与电视在同一局域网。接收页面关闭后，传输服务会停止。请只安装你信任的应用。</p>
          </main>
          <script>
            const fileInput = document.getElementById('file');
            const pinInput = document.getElementById('pin');
            const button = document.getElementById('send');
            const progress = document.getElementById('progress');
            const status = document.getElementById('status');
            function busy(value) {
              button.disabled = value;
              fileInput.disabled = value;
              pinInput.disabled = value;
            }
            fileInput.addEventListener('change', () => {
              const file = fileInput.files[0];
              status.textContent = file ? file.name + ' · ' + (file.size / 1048576).toFixed(1) + ' MB' : '尚未选择文件';
              progress.hidden = true;
            });
            button.addEventListener('click', () => {
              const file = fileInput.files[0];
              if (!file) { status.textContent = '请先选择 APK 文件'; return; }
              if (!/\.apk$/i.test(file.name)) { status.textContent = '请选择 .apk 文件'; return; }
              if (!file.size || file.size > 256 * 1024 * 1024) { status.textContent = '请选择不超过 256 MB 的 APK 文件'; return; }
              const pin = pinInput.value.trim();
              if (!pin) { status.textContent = '请输入电视上显示的接收码'; pinInput.focus(); return; }
              busy(true);
              progress.hidden = false;
              progress.value = 0;
              status.textContent = '正在发送…';
              const request = new XMLHttpRequest();
              request.open('POST', '/upload');
              request.timeout = 10 * 60 * 1000;
              request.setRequestHeader('Content-Type', 'application/octet-stream');
              request.setRequestHeader('X-Astra-Pin', pin);
              request.upload.onprogress = event => {
                if (event.lengthComputable) {
                  const percent = Math.round(event.loaded / event.total * 100);
                  progress.value = percent;
                  status.textContent = percent < 100 ? '正在发送 ' + percent + '%' : '传输完成，正在检查 APK…';
                }
              };
              request.onload = () => {
                busy(false);
                status.textContent = request.responseText || '接收失败，请重试';
                if (request.status === 200) progress.value = 100;
              };
              request.onerror = () => { busy(false); status.textContent = '连接已断开，请确认电视的接收页面仍然打开，并检查网络'; };
              request.ontimeout = () => { busy(false); status.textContent = '传输超时，请重试'; };
              request.send(file);
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}
