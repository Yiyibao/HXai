"""Trusted per-task hosting duration; never a grant to other tasks or providers."""
import math
import time

SCOPE='THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER'


def validate_grant(grant, *, fresh=False):
    if not isinstance(grant,dict) or set(grant) not in ({'expiresAt','dataScope'},{'mode','expiresAt','dataScope'}):
        raise ValueError('invalid_hosting_grant')
    if grant['dataScope']!=SCOPE:
        raise ValueError('explicit_hosting_scope_required')
    mode=grant.get('mode','UNTIL')
    expiry=grant['expiresAt']
    if mode=='UNTIL_REVOKED':
        if expiry is not None:raise ValueError('continuous_grant_has_no_deadline')
    elif mode=='UNTIL':
        if type(expiry) not in (int,float) or not math.isfinite(expiry):
            raise ValueError('invalid_hosting_expiry')
        if fresh and not time.time()<expiry<=time.time()+86400*30:
            raise ValueError('invalid_hosting_expiry')
    else:raise ValueError('unknown_hosting_mode')


def grant_expired(grant):
    validate_grant(grant)
    return grant.get('mode','UNTIL')=='UNTIL' and grant['expiresAt']<=time.time()
