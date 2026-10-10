#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_FILE="${1:-$ROOT_DIR/packaging/flatpak/gradle-sources.json}"
VM_FILE="$ROOT_DIR/gradle/verification-metadata.xml"

# Always remove verification-metadata.xml on exit so dependency verification is never left enabled
trap 'rm -f "$VM_FILE"' EXIT INT TERM

echo "Resolving dependencies for :desktop:createDistributable with verification metadata..." >&2
rm -f "$VM_FILE"
(
    cd "$ROOT_DIR"
    ./gradlew -Pkotlin.compiler.execution.strategy=in-process \
        :desktop:createDistributable \
        -PversionName=0.1.0 \
        -Pflatpak \
        --write-verification-metadata sha256 \
        --no-build-cache \
        --rerun-tasks \
        --console=plain
)

if [ ! -f "$VM_FILE" ]; then
    echo "Error: $VM_FILE was not generated" >&2
    exit 1
fi

python3 - "$ROOT_DIR" "$VM_FILE" "$OUTPUT_FILE" << 'PYEOF'
import sys, os, hashlib, json, re, urllib.request, xml.etree.ElementTree as ET

root_dir = sys.argv[1]
vm_file = sys.argv[2]
output_file = sys.argv[3]

home = os.path.expanduser("~")
modules_dir = os.path.join(home, ".gradle", "caches", "modules-2")
files_dir = os.path.join(modules_dir, "files-2.1")

if not os.path.exists(files_dir):
    print(f"Error: {files_dir} does not exist", file=sys.stderr)
    sys.exit(1)

# Read Gradle's recorded download URLs
url_data = b""
for md in os.listdir(modules_dir):
    if md.startswith("metadata-"):
        bin_path = os.path.join(modules_dir, md, "resource-at-url.bin")
        if os.path.exists(bin_path):
            with open(bin_path, "rb") as f:
                url_data += f.read()

def find_url(group, artifact, version, filename, sha1):
    rel_path = f"files-2.1/{group}/{artifact}/{version}/{sha1}/{filename}".encode()
    pos = url_data.find(rel_path)
    if pos != -1:
        pos_after = pos + len(rel_path)
        next_http_1 = url_data.find(b"http://", pos_after)
        next_http_2 = url_data.find(b"https://", pos_after)
        candidates = [p for p in (next_http_1, next_http_2) if p != -1]
        if candidates:
            next_http = min(candidates)
            if next_http - pos_after < 500:
                raw = url_data[next_http:next_http + 500].decode("utf-8", "ignore")
                m = re.match(r"(https?://[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;%=]+)", raw)
                if m:
                    return m.group(1)
    return None

candidates_tpl = [
    "https://repo1.maven.org/maven2/{group_path}/{artifact}/{version}/{filename}",
    "https://dl.google.com/dl/android/maven2/{group_path}/{artifact}/{version}/{filename}",
    "https://plugins.gradle.org/m2/{group_path}/{artifact}/{version}/{filename}",
    "https://maven.pkg.jetbrains.space/public/p/compose/dev/{group_path}/{artifact}/{version}/{filename}",
]

def check_url(url):
    req = urllib.request.Request(url, method="HEAD")
    req.add_header("User-Agent", "Mozilla/5.0")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status == 200
    except Exception:
        return False

def probe_url(group, artifact, version, filename):
    group_path = group.replace(".", "/")
    for tpl in candidates_tpl:
        url = tpl.format(group_path=group_path, artifact=artifact, version=version, filename=filename)
        if check_url(url):
            return url
    return None

tree = ET.parse(vm_file)
root = tree.getroot()
ns = {"v": "https://schema.gradle.org/dependency-verification"}

entries = []
seen = set()

def add_entry(grp, art, ver, local_fname, sha1, file_path, sha256_val=None):
    if not sha256_val:
        with open(file_path, "rb") as f:
            sha256_val = hashlib.sha256(f.read()).hexdigest()
    
    url = find_url(grp, art, ver, local_fname, sha1)
    if not url:
        url = probe_url(grp, art, ver, local_fname)
    if not url:
        grp_path = grp.replace(".", "/")
        url = f"https://repo1.maven.org/maven2/{grp_path}/{art}/{ver}/{local_fname}"
        
    url_fname = url.split("/")[-1]
    grp_path = grp.replace(".", "/")
    dest = f"flatpak-repo/{grp_path}/{art}/{ver}"
    
    key = (dest, url_fname)
    if key in seen:
        return
    seen.add(key)
    
    entries.append({
        "type": "file",
        "url": url,
        "sha256": sha256_val,
        "dest": dest,
        "dest-filename": url_fname,
    })

# 1. Add all artifacts from verification-metadata.xml
components = root.findall(".//v:component", ns)
for comp in components:
    grp = comp.get("group")
    art = comp.get("name")
    ver = comp.get("version")
    comp_dir = os.path.join(files_dir, grp, art, ver)
    
    for artifact in comp.findall("./v:artifact", ns):
        fname = artifact.get("name")
        sha256_val = artifact.find("./v:sha256", ns).get("value")
        
        sha1 = ""
        file_path = None
        if os.path.isdir(comp_dir):
            for s in os.listdir(comp_dir):
                c = os.path.join(comp_dir, s, fname)
                if os.path.isfile(c):
                    sha1 = s
                    file_path = c
                    break
        if file_path:
            add_entry(grp, art, ver, fname, sha1, file_path, sha256_val)
            
    # 2. Also ensure component's .pom is included
    pom_name = f"{art}-{ver}.pom"
    if os.path.isdir(comp_dir):
        for s in os.listdir(comp_dir):
            c = os.path.join(comp_dir, s, pom_name)
            if os.path.isfile(c):
                add_entry(grp, art, ver, pom_name, s, c)
                break

# 3. Recursively discover parent POMs and imported BOMs
def get_pom_info(pom_file):
    try:
        tree = ET.parse(pom_file)
        r = tree.getroot()
        pns = ""
        if r.tag.startswith("{"):
            pns = r.tag.split("}")[0] + "}"
        parent_info = None
        parent = r.find(f"./{pns}parent")
        if parent is not None:
            g = parent.find(f"./{pns}groupId")
            a = parent.find(f"./{pns}artifactId")
            v = parent.find(f"./{pns}version")
            if g is not None and a is not None and v is not None:
                parent_info = (g.text.strip(), a.text.strip(), v.text.strip())
        props = {}
        pv = r.find(f"./{pns}version")
        if pv is not None and pv.text:
            props["project.version"] = pv.text.strip()
            props["version"] = pv.text.strip()
        elif parent_info:
            props["project.version"] = parent_info[2]
            props["version"] = parent_info[2]
        for p in r.findall(f".//{pns}properties/*"):
            tag = p.tag.replace(pns, "")
            if p.text:
                props[tag] = p.text.strip()
        boms = []
        for dep in r.findall(f".//{pns}dependencyManagement//{pns}dependency"):
            scope = dep.find(f"./{pns}scope")
            if scope is not None and scope.text and scope.text.strip() == "import":
                g = dep.find(f"./{pns}groupId")
                a = dep.find(f"./{pns}artifactId")
                v = dep.find(f"./{pns}version")
                if g is not None and a is not None and v is not None:
                    g_text = g.text.strip()
                    a_text = a.text.strip()
                    v_text = v.text.strip()
                    if v_text.startswith("${") and v_text.endswith("}"):
                        v_text = props.get(v_text[2:-1], v_text)
                    if g_text.startswith("${") and g_text.endswith("}"):
                        g_text = props.get(g_text[2:-1], g_text)
                    if not v_text.startswith("${"):
                        boms.append((g_text, a_text, v_text))
        return parent_info, boms
    except Exception:
        return None, []

pom_queue = []
for e in entries:
    if e["dest-filename"].endswith(".pom"):
        parts = e["dest"].split("/")
        grp = ".".join(parts[1:-2])
        art = parts[-2]
        ver = parts[-1]
        fname = e["dest-filename"]
        cdir = os.path.join(files_dir, grp, art, ver)
        if os.path.isdir(cdir):
            for s in os.listdir(cdir):
                c = os.path.join(cdir, s, fname)
                if os.path.isfile(c):
                    pom_queue.append(c)
                    break

checked_poms = set()
while pom_queue:
    p = pom_queue.pop(0)
    if p in checked_poms:
        continue
    checked_poms.add(p)
    parent_info, boms = get_pom_info(p)
    candidates = []
    if parent_info:
        candidates.append(parent_info)
    candidates.extend(boms)
    for grp, art, ver in candidates:
        fname = f"{art}-{ver}.pom"
        cdir = os.path.join(files_dir, grp, art, ver)
        if os.path.isdir(cdir):
            for s in os.listdir(cdir):
                c = os.path.join(cdir, s, fname)
                if os.path.isfile(c):
                    add_entry(grp, art, ver, fname, s, c)
                    pom_queue.append(c)
                    break

entries.sort(key=lambda x: (x["dest"], x["dest-filename"]))

os.makedirs(os.path.dirname(os.path.abspath(output_file)), exist_ok=True)
with open(output_file, "w") as f:
    json.dump(entries, f, indent=2)
    f.write("\n")

print(f"Wrote {len(entries)} sources to {output_file}", file=sys.stderr)
PYEOF
