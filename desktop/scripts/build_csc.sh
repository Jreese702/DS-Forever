#!/bin/bash
# Build DS Forever Desktop with the Build Tools' Roslyn csc (no .NET SDK on this PC): Core, the app, the tests -> $OUT
# (a fallback for a PC with Visual Studio Build Tools but no .NET SDK; the SDK route in README.md stays the normal one)
set -e
D=/e/dsf-wt-desktop/desktop
OUT=${OUT:-$D/build-csc}
VER=${VER:-0.2.1}
CSC="/c/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/MSBuild/Current/Bin/Roslyn/csc.exe"
REF="C:/Program Files (x86)/Reference Assemblies/Microsoft/Framework/.NETFramework/v4.8"
mkdir -p "$OUT"
W=$(cygpath -w "$OUT")
cat > "$OUT/AssemblyInfo.cs" <<EOF2
[assembly: System.Reflection.AssemblyVersion("$VER.0")]
[assembly: System.Reflection.AssemblyFileVersion("$VER.0")]
[assembly: System.Reflection.AssemblyInformationalVersion("$VER")]
[assembly: System.Reflection.AssemblyProduct("DS Forever Desktop")]
[assembly: System.Reflection.AssemblyCompany("DS Forever")]
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8", FrameworkDisplayName = ".NET Framework 4.8")]
EOF2
COMMON=(-nologo -noconfig -nostdlib -langversion:10 -optimize+ -debug:portable -nowarn:1591,CS1998,CS0649,CS0169,CS0414
  "-r:$REF/mscorlib.dll" "-r:$REF/System.dll" "-r:$REF/System.Core.dll" "-r:$REF/System.Xml.dll" "-r:$REF/System.Xml.Linq.dll"
  "-r:$REF/System.Drawing.dll" "-r:$REF/System.Web.Extensions.dll" "-r:$REF/System.Net.Http.dll" "-r:$REF/System.IO.Compression.dll" "-r:$REF/System.IO.Compression.FileSystem.dll" "-r:$REF/System.Security.dll" "-r:$REF/Facades/netstandard.dll"
  "-r:$REF/Facades/System.Runtime.dll" "-r:$REF/Facades/System.ValueTuple.dll")
cd "$D/src/DsfDesktop.Core"
"$CSC" "${COMMON[@]}" -target:library -out:"$W\\DsfDesktop.Core.dll" -resource:"Bridge\\testpage.html,DsfDesktop.testpage.html" \
  $(find . -name "*.cs" -not -path "./obj/*" -not -path "./bin/*" | sed 's#^\./##; s#/#\\#g') "$W\\AssemblyInfo.cs"
cd "$D/src/DsfDesktop"
"$CSC" "${COMMON[@]}" -target:winexe -out:"$W\\DsForeverDesktop.exe" -win32manifest:app.manifest \
  "-r:$REF/System.Windows.Forms.dll" "-r:$REF/System.Drawing.dll" "-r:$W\\DsfDesktop.Core.dll" \
  -resource:"..\\..\\..\\app\\src\\main\\ic_launcher-playstore.png,DsfDesktop.mark.png" \
  $(find . -name "*.cs" -not -path "./obj/*" -not -path "./bin/*" | sed 's#^\./##; s#/#\\#g') "$W\\AssemblyInfo.cs"
cd "$D/tests/DsfDesktop.Tests"
"$CSC" "${COMMON[@]}" -target:exe -out:"$W\\DsfDesktop.Tests.exe" "-r:$W\\DsfDesktop.Core.dll" \
  $(find . -name "*.cs" -not -path "./obj/*" -not -path "./bin/*" | sed 's#^\./##; s#/#\\#g') "$W\\AssemblyInfo.cs"
echo "BUILT $OUT"
