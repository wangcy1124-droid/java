#!/usr/bin/env python3
"""Run JMeter, retain raw evidence, then reconcile accepted orders with real stores."""
import argparse
import base64
import collections
import csv
import json
import math
import os
import platform
import subprocess
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from prepare import ROOT, redis, sql, request


def queues():
    base = os.environ.get('RABBITMQ_MANAGEMENT_URL', 'http://127.0.0.1:15672')
    auth = base64.b64encode((os.getenv('RABBITMQ_USER','guest')+':'+os.getenv('RABBITMQ_PASSWORD','guest')).encode()).decode()
    result = {}
    for name in ('seckill.order.queue', 'seckill.order.dlq', 'seckill.order.parking'):
        path = '/api/queues/'+urllib.parse.quote(os.getenv('RABBITMQ_VHOST','/'),safe='')+'/'+name
        req = urllib.request.Request(base.rstrip('/')+path, headers={'Authorization':'Basic '+auth})
        with urllib.request.urlopen(req, timeout=10) as r:
            q = json.load(r)
        result[name] = {k:q[k] for k in ('messages_ready','messages_unacknowledged','consumers')}
    return result


def empty(q):
    return all(q[n]['messages_ready']+q[n]['messages_unacknowledged']==0 for n in ('seckill.order.queue','seckill.order.dlq'))


def metrics(rows, seconds=None):
    if not rows:
        return None
    latency=sorted(int(r['elapsed']) for r in rows)
    seconds=seconds or (max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)-min(int(r['timeStamp']) for r in rows))/1000
    ok=sum(r['success']=='true' for r in rows)
    return dict(samples=len(rows), seconds=round(seconds,3), tps=round(len(rows)/seconds,3),
                success_tps=round(ok/seconds,3), errors=len(rows)-ok, error_pct=round((len(rows)-ok)*100/len(rows),5),
                p95_ms=latency[math.ceil(len(latency)*.95)-1], p99_ms=latency[math.ceil(len(latency)*.99)-1],
                max_ms=latency[-1], mean_ms=round(sum(latency)/len(latency),3),
                max_threads=max(int(r['allThreads']) for r in rows), min_threads=min(int(r['allThreads']) for r in rows))


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--data', required=True, type=Path)
    p.add_argument('--out', required=True, type=Path)
    p.add_argument('--jmeter', default='jmeter')
    p.add_argument('--threads',type=int,default=500)
    p.add_argument('--ramp',type=int,default=10)
    p.add_argument('--duration',type=int,default=60)
    p.add_argument('--drain-timeout',type=int,default=120)
    p.add_argument('--target-rps',type=float,default=900)
    p.add_argument('--steady-margin',type=int,default=2)
    p.add_argument('--environment',required=True,type=Path,help='Non-secret deployment/JVM details JSON')
    a=p.parse_args()
    if a.threads<1 or a.duration<=a.ramp+2*a.steady_margin or a.ramp<0 or a.steady_margin<0 or a.target_rps<=0:
        p.error('require threads>0 and duration>ramp>=0')
    m=json.loads((a.data/'manifest.json').read_text()); v=int(m['voucher_id'])
    if (datetime.now().astimezone()-datetime.fromisoformat(m['created_at'])).total_seconds()+a.duration>m['session_ttl_seconds']:
        raise RuntimeError('Fixture sessions too old; prepare fresh data')
    assert sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d'%v)=='0', 'used voucher'
    assert redis('GET','seckill:stock:'+str(v))==str(m['stock'])
    assert sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d'%v)==str(m['stock'])
    before=queues()
    assert empty(before) and before['seckill.order.queue']['consumers']>0, before
    with (a.data/'users.csv').open() as f: first=next(csv.DictReader(f))
    assert str(request('/user/me',token=first['token'])['id'])==first['userId']
    target=urllib.parse.urlsplit(m['base_url'])
    a.out.mkdir(parents=True,exist_ok=False)
    # A failed or interrupted run must also get a new voucher and CSV.
    (a.data/'.used').open('x').close()
    deployment=json.loads(a.environment.read_text())
    environment=dict(recorded_at=datetime.now().astimezone().isoformat(), load_generator=dict(
        os=platform.platform(), logical_cpus=os.cpu_count(), cpu=next((x.split(':',1)[1].strip() for x in Path('/proc/cpuinfo').read_text().splitlines() if x.startswith('model name')),''),
        memory_kb=next(x for x in Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemTotal'))),
        deployment=deployment,
        mysql=sql('SELECT VERSION(),@@innodb_buffer_pool_size,@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@max_connections').split('\t'),
        redis_persistence={line.split(':',1)[0]:line.split(':',1)[1] for line in redis('INFO','persistence').splitlines() if line.startswith(('aof_enabled:','aof_last_write_status:'))})
    (a.out/'environment.json').write_text(json.dumps(environment,indent=2,ensure_ascii=False)+'\n')
    cmd=[a.jmeter,'-n','-t',str(ROOT/'jmeter/seckill-500.jmx'),'-l',str(a.out.resolve()/'raw.jtl'),'-j',str(a.out.resolve()/'jmeter.log'),
         '-Jthreads='+str(a.threads),'-Jramp='+str(a.ramp),'-Jduration='+str(a.duration),'-Jtarget_rpm='+str(a.target_rps*60),
         '-Jhost='+target.hostname,'-Jport='+str(target.port or (443 if target.scheme=='https' else 80)),
         '-Jprotocol='+target.scheme,'-Jprefix='+target.path.rstrip('/'),
         '-Jdata='+str((a.data/'users.csv').resolve()),'-Jassertion='+str(ROOT/'jmeter/assert-order.groovy'),
         '-Jsample_variables=userId,voucherId,orderId','-Jjmeter.save.saveservice.output_format=csv',
         '-Jjmeter.save.saveservice.assertion_results_failure_message=true',
         '-Jjmeter.save.saveservice.timestamp_format=ms','-Jjmeter.save.saveservice.thread_counts=true',
         '-Jhttpclient4.retrycount=0']
    (a.out/'parameters.json').write_text(json.dumps(dict(threads=a.threads,ramp=a.ramp,duration=a.duration,target_rps=a.target_rps,steady_margin=a.steady_margin,manifest=m,command=cmd),indent=2)+'\n')
    history=[]
    start=time.monotonic()
    with (a.out/'console.txt').open('w') as log:
        proc=subprocess.Popen(cmd,stdout=log,stderr=subprocess.STDOUT)
        try:
            while proc.poll() is None:
                history.append(dict(seconds=round(time.monotonic()-start,3), queues=queues()))
                time.sleep(2)
        except BaseException:
            proc.terminate(); proc.wait(timeout=30)
            raise
    end=time.monotonic()
    assert proc.returncode==0, 'JMeter failed: see console.txt'
    with (a.out/'raw.jtl').open() as f: rows=list(csv.DictReader(f))
    assert rows, 'no HTTP samples'
    accepted={r['orderId']:r['userId'] for r in rows if r['success']=='true'}
    assert len(accepted)==sum(r['success']=='true' for r in rows), 'duplicate returned order IDs'
    while True:
        q=queues()
        actual=sql('SELECT id,user_id,status FROM tb_voucher_order WHERE voucher_id=%d'%v)
        records=[line.split('\t') for line in actual.splitlines() if line]
        actual_ids={r[0]:r[1] for r in records}
        pending=sql("SELECT COUNT(*) FROM tb_order_delivery WHERE voucher_id=%d AND state='OPEN'"%v)
        history.append(dict(seconds=round(time.monotonic()-start,3),queues=q,orders=len(records)))
        if (empty(q) and all(actual_ids.get(i)==u for i,u in accepted.items()) and pending=='0') or time.monotonic()-end>a.drain_timeout:
            break
        time.sleep(2)
    drain=round(time.monotonic()-end,3)
    rstock=int(redis('GET','seckill:stock:'+str(v)))
    dbstock=int(sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d'%v))
    reservations=int(redis('SCARD','seckill:order:'+str(v)))-1
    owner_count=int(redis('HLEN','seckill:owner:'+str(v)))
    delivery_states=sql('SELECT state,COUNT(*) FROM tb_order_delivery WHERE voucher_id=%d GROUP BY state'%v)
    checks=dict(accepted_orders_exactly_persisted=actual_ids==accepted, all_pending=all(r[2]=='1' for r in records),
        unique_users=len({r[1] for r in records})==len(records),
        inventory_consistent=rstock==dbstock==m['stock']-len(records)>=0,
        qualification_consistent=reservations==owner_count==len(records),
        queues_drained=empty(q),parking_unchanged=q['seckill.order.parking']==before['seckill.order.parking'],
        delivery_created_only=delivery_states=='CREATED\t'+str(len(records)))
    first_ms=min(int(r['timeStamp']) for r in rows)
    steady=[r for r in rows if first_ms+(a.ramp+a.steady_margin)*1000<=int(r['timeStamp']) and int(r['timeStamp'])+int(r['elapsed'])<=first_ms+(a.duration-a.steady_margin)*1000]
    stats=metrics(rows); sustained=metrics(steady,a.duration-a.ramp-2*a.steady_margin)
    result=dict(overall=stats,steady=sustained,steady_definition='Requests fully contained in [first sample + ramp + margin, first sample + duration - margin]; includes business/HTTP failures; default margin 2s excludes scheduling edges',
        dataset_exhausted=len(rows)>=m['rows'],checks=checks,accepted=len(accepted),orders=len(records),
        redis_stock=rstock,mysql_stock=dbstock,qualifications=reservations,owners=owner_count,
        delivery_states=delivery_states,drain_seconds_after_jmeter=drain,
        http_codes=dict(collections.Counter(r['responseCode'] for r in rows)),
        failure_examples=[r.get('failureMessage','') for r in rows if r['success']!='true'][:10])
    result['numeric_target_met']=bool(sustained and a.threads==500 and a.duration-a.ramp-2*a.steady_margin>=30 and
        sustained['min_threads']==500 and sustained['success_tps']>=800 and sustained['p95_ms']<=300 and
        sustained['error_pct']<.1 and stats['error_pct']<.1 and not result['dataset_exhausted'] and all(checks.values()))
    result['server_target_verified']=bool(deployment.get('verified_server_environment') is True and result['numeric_target_met'])
    (a.out/'summary.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    (a.out/'queues.json').write_text(json.dumps(dict(before=before,history=history,after=q),indent=2)+'\n')
    buckets=collections.defaultdict(list)
    for r in rows: buckets[(int(r['timeStamp'])-first_ms)//1000].append(r)
    with (a.out/'per-second.csv').open('w',newline='') as f:
        writer=csv.writer(f);writer.writerow(['second','samples','errors','p95_ms','max_threads'])
        for sec,b in sorted(buckets.items()):
            s=metrics(b,1);writer.writerow([sec,s['samples'],s['errors'],s['p95_ms'],s['max_threads']])
    print(json.dumps(result,indent=2,ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit('Order reconciliation failed; retain raw evidence and investigate')
    if stats['errors']:
        raise SystemExit('HTTP/business failures observed; see summary.json')


if __name__=='__main__':
    main()
