#!/usr/bin/env python3
"""Read-only follow-up for a run whose asynchronous drain timed out. Keeps initial result unchanged."""
import argparse
import collections
import csv
import json
import time
from datetime import datetime
from pathlib import Path
from run import queues, empty, redis, sql


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--result',required=True,type=Path)
    p.add_argument('--wait-seconds',type=int,default=0)
    a=p.parse_args()
    m=json.loads((a.result/'parameters.json').read_text())['manifest'];v=int(m['voucher_id'])
    with (a.result/'raw.jtl').open() as f:
        accepted={r['orderId']:r['userId'] for r in csv.DictReader(f) if r['success']=='true'}
    deadline=time.monotonic()+a.wait_seconds
    while True:
        q=queues()
        if empty(q) or time.monotonic()>=deadline: break
        time.sleep(min(10,max(0,deadline-time.monotonic())))
    orders={r[0]:r[1:] for r in (line.split('\t') for line in sql('SELECT id,user_id,status FROM tb_voucher_order WHERE voucher_id=%d'%v).splitlines()) if r[0]}
    deliveries={r[0]:r[1:] for r in (line.split('\t') for line in sql('SELECT order_id,user_id,state FROM tb_order_delivery WHERE voucher_id=%d'%v).splitlines()) if r[0]}
    states=collections.Counter(x[1] for x in orders.values())
    cancelled={i for i,r in deliveries.items() if r[1]=='CANCELLED'}
    active=sum(x[1]!='4' for x in orders.values())
    dbstock=int(sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d'%v))
    rstock=int(redis('GET','seckill:stock:'+str(v)))
    qualifications=int(redis('SCARD','seckill:order:'+str(v)))-1
    owners=int(redis('HLEN','seckill:owner:'+str(v)))
    releases=int(sql('SELECT COUNT(*) FROM tb_order_stock_release WHERE voucher_id=%d AND completed=0'%v))
    accounted={i for i,u in accepted.items() if
        (i in orders and orders[i][0]==u and deliveries.get(i)==[u,'CREATED']) or
        (i in cancelled and deliveries[i][0]==u and i not in orders)}
    checks=dict(queues_drained=empty(q), every_acceptance_accounted=accounted==set(accepted),
        no_unrequested_deliveries=set(deliveries)<=set(accepted),
        active_inventory_consistent=dbstock==rstock==m['stock']-active>=0,
        active_qualifications_consistent=qualifications==owners==active,
        no_pending_stock_release=releases==0)
    result=dict(recorded_at=datetime.now().astimezone().isoformat(),voucher_id=v,accepted=len(accepted),
        orders=len(orders),order_status_counts=dict(states),cancelled_before_creation=len(cancelled),
        unaccounted=len(set(accepted)-accounted),mysql_stock=dbstock,redis_stock=rstock,
        qualifications=qualifications,owners=owners,pending_releases=releases,queues=q,checks=checks)
    # Timestamped so later paid/closed states do not overwrite earlier observations.
    out=a.result/('settlement-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.json')
    out.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(result,indent=2,ensure_ascii=False))
    if not all(checks.values()): raise SystemExit('Not fully settled; initial performance result is unchanged')


if __name__=='__main__': main()
