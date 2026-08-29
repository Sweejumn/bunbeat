import sqlite3

c = sqlite3.connect("data/runbpm.db")
for r in c.execute("select filename, bpm_error from songs where filename like 'test_format%'"):
    print(r)
