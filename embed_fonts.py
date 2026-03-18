import urllib.request, base64, re, sys

UA = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36'

def fetch_css(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req) as r:
        return r.read().decode('utf-8')

def fetch_bytes(url):
    with urllib.request.urlopen(url) as r:
        return r.read()

print("Fetching font CSS...")
css1 = fetch_css('https://fonts.googleapis.com/css2?family=Orbitron:wght@400;700;900&display=swap')
css2 = fetch_css('https://fonts.googleapis.com/css2?family=Share+Tech+Mono&display=swap')
combined = css1 + '\n' + css2

font_urls = re.findall(r'url\((https://[^)]+)\)', combined)
print(f"Found {len(font_urls)} font files")

for url in font_urls:
    data = fetch_bytes(url)
    b64 = base64.b64encode(data).decode('ascii')
    combined = combined.replace(url, f'data:font/woff2;base64,{b64}')
    print(f"  embedded: ...{url[-30:]}")

with open('app/src/main/assets/index.html', 'r', encoding='utf-8') as f:
    html = f.read()

print(f"\nHTML size before: {len(html)} chars")

# Regex replace — catches any variation of the Google Fonts link
pattern = r'<link[^>]*fonts\.googleapis\.com[^>]*>'
replacement = f'<style>/* FONTS EMBEDDED OFFLINE */\n{combined}\n</style>'
new_html, count = re.subn(pattern, replacement, html)

print(f"Font link replacements made: {count}")
print(f"HTML size after: {len(new_html)} chars")

if count == 0:
    print("WARNING: No font link found to replace!")
    sys.exit(1)

with open('app/src/main/assets/index.html', 'w', encoding='utf-8') as f:
    f.write(new_html)

print("Fonts embedded successfully!")
