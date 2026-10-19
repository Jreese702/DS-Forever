# the client tables the harness reads (by name from the listfile), into <work>/db2: local CASC reads only
import sys, os, csv
sys.path.insert(0, os.environ.get('CASC_TOOLS', r'E:/wc-addon-map/tools/icons'))
import casc
h = casc.open_storage(os.environ.get('WOW_DIR', r'C:\Program Files (x86)\World of Warcraft'), os.environ.get('WOW_PRODUCT', 'wow_classic_beta'), locale=0x2)
WORK = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work')
os.makedirs(os.path.join(WORK, 'db2'), exist_ok=True)
NAMES = ['AreaTable', 'Map', 'UiMapAssignment', 'WMOAreaTable', 'TaxiNodes', 'TaxiPath', 'TaxiPathNode', 'UiTextureAtlas', 'UiTextureAtlasMember', 'LiquidType']
want = {'dbfilesclient/%s.db2' % n.lower(): n for n in NAMES}
for row in csv.reader(open(os.environ.get('LISTFILE', 'E:/wc-scratch-c9979a42/casc/community-listfile.csv'), encoding='utf-8', errors='ignore'), delimiter=';'):
    if len(row) >= 2 and row[1].lower() in want:
        b = casc.read_fileid(h, int(row[0]))
        if b: open(os.path.join(WORK, 'db2', want[row[1].lower()] + '.db2'), 'wb').write(b); print(want[row[1].lower()], len(b))
