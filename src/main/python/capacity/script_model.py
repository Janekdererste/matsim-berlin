#!/usr/bin/env python

from os import makedirs
from os.path import join

import random
import sklearn.ensemble

from sklearn.model_selection import KFold
from sklearn.metrics import mean_absolute_error

import m2cgen as m2c
import pandas as pd
import numpy as np
import seaborn as sns

import optuna

from models import create_regressor, model_to_java, model_to_py
from features import build_datasets

#%%

d = "../../../../"

dfs = build_datasets(d + "input/sumo.net-edges.csv.gz",
                     d + "input/result_intersections_scenario-base.csv",
                     d + "input/result_routes_scenario-base.csv")


#%%

targets = dfs.keys()

def get(idx, t):
    
    if idx is not None:
        df = dfs[t].iloc[idx]        
    else:
        df = dfs[t]
    
    return df.drop(columns=["target"]), df.target.to_numpy()
    

scaler = {}

for t in targets:

    _scaler = sklearn.preprocessing.StandardScaler(with_mean=True)        
    
    df = get(None, t)[0]
    
    norm = ["length", "speed", "numFoes", "numLanes", "junctionSize"]
    
    scaler[t] = sklearn.compose.ColumnTransformer([
          ("scale", _scaler, [df.columns.get_loc(x) for x in norm]) # column indices
        ],
        remainder="passthrough"
    )
    
    scaler[t].fit(df)
    

print("Model targets", targets)

#%%

def best_model(ms, t):
    
    errors = []
    for m in ms:

        X, y = get(None, t)
        X = scaler[t].transform(X)

        pred = m.predict(X)
        err = mean_absolute_error(y, pred)
        
        errors.append((m, err))
        
    errors = sorted(errors, key=lambda m : m[1])
    
    return errors[0]

#%%

## Feature selection

from xgboost import XGBRegressor
from sklearn.feature_selection import RFECV

model = XGBRegressor(max_depth=6, n_estimators=150)

rfecv = RFECV(estimator=model, step=1, cv=KFold(n_splits=5, shuffle=True), scoring='neg_mean_absolute_error')

X, y = get(None, "capacity_traffic_light")
X = scaler[t].transform(X)

rfecv.fit(X, y)

#Selected features
#print(X.columns[rfecv.get_support()])
print("Optimal number of features : %d" % rfecv.n_features_)

#%%

fold = KFold(n_splits=6, shuffle=True)
n_trials = 150


classifier = {
     'mean',
     'XGBRFRegressor',
     'XGBRegressor',
     'RandomForestRegressor',
     'ExtraTreesRegressor',
     'LGBMRegressor',
     'DecisionTreeRegressor',
     'PassiveAggressiveRegressor',
    # More
    #   'SVR',
    #   'KernelSVC',
    #   'QLatticeRegressor',
    #   'LinearSVR',
    #   'Ridge',
    #   'SGDRegressor',
    #   'LogisticRegression',
    #   'AdaGradRegressor',
    #   'CDRegressor',
    #   'FistaRegressor',
    #   'SDCARegressor',
    #   'Lasso',
    #   'ElasticNet'
}


def objective(classifier_name, target):
    global model

    def _fn(trial):
        global model

        r = random.Random(42)

        random_state = r.getrandbits(31)

        seq = iter(fold.split(dfs[target]))

        error = 0
        i = 0

        candidates = []

        for train, test in seq:

            model = create_regressor(trial, classifier_name, random_state)
            
            candidates.append(model)


            X, y = get(train, target)
            X = scaler[t].transform(X)

            model.fit(X, y)

            Xval, yval = get(test, target)
            Xval = scaler[t].transform(Xval)

            pred = model.predict(Xval)

            error += mean_absolute_error(yval, pred)

            i += 1

        best = best_model(candidates, t)[0]

        return error / i

    return _fn

def callback(study, trial):
    global best
    global model
    if study.best_trial == trial:
        best = model

models = {}

for t in targets:

    print("Training", t)

    models[t] = {}

    for m in classifier:
        print("Running model", m)

        study = optuna.create_study(sampler=optuna.samplers.TPESampler(seed=42), direction='minimize')
        study.optimize(objective(m, t), n_trials=n_trials, callbacks=[callback], show_progress_bar=True)

        models[t][m] = best

#%%


for t in targets:
    
    print("#### ", t)
    
    m = best_model(models[t].values(), t)
    
    print("Best model", m)

    makedirs("gen_code", exist_ok=True)
    
    with open(join("gen_code", "__init__.py"), "w") as f:
        f.write("")

    with open(join("gen_code", t.capitalize() + ".java"), "w") as f:
        code = model_to_java(t, m[0], scaler[t], get(None, t)[0])
        f.write(code)

    with open(join("gen_code", t + ".py"), "w") as f:
        code = model_to_py(t, m[0], scaler[t], get(None, t)[0])
        f.write("# -*- coding: utf-8 -*-\n")
        f.write(code)

#%%

"""

# Current models

####  speedRelative_priority
Best model (XGBRegressor(alpha=0.02177970660809249, base_score=0.5, booster='gbtree',
             callbacks=None, colsample_bylevel=1, colsample_bynode=0.9,
             colsample_bytree=0.9, early_stopping_rounds=None,
             enable_categorical=False, eta=0.42368870348360776,
             eval_metric='mae', feature_types=None, gamma=0.010202903364639485,
             gpu_id=-1, grow_policy='depthwise', importance_type=None,
             interaction_constraints='', lambda=0.08513652356917698,
             learning_rate=0.42368871, max_bin=256, max_cat_threshold=64,
             max_cat_to_onehot=4, max_delta_step=0, max_depth=4, max_leaves=0,
             min_child_weight=9, missing=nan, monotone_constraints='()',
             n_estimators=30, n_jobs=0, ...), 0.036465250251300194)
####  speedRelative_right_before_left
Best model (RandomForestRegressor(max_depth=4, n_estimators=25, oob_score=True,
                      random_state=1373158606), 0.029128045291676417)
####  speedRelative_traffic_light
Best model (XGBRegressor(alpha=0.4114218691246758, base_score=0.5, booster='gbtree',
             callbacks=None, colsample_bylevel=1, colsample_bynode=0.9,
             colsample_bytree=0.9, early_stopping_rounds=None,
             enable_categorical=False, eta=0.4558030077082193,
             eval_metric='mae', feature_types=None, gamma=0.014543024252670228,
             gpu_id=-1, grow_policy='depthwise', importance_type=None,
             interaction_constraints='', lambda=0.09033625662221197,
             learning_rate=0.455803007, max_bin=256, max_cat_threshold=64,
             max_cat_to_onehot=4, max_delta_step=0, max_depth=4, max_leaves=0,
             min_child_weight=5, missing=nan, monotone_constraints='()',
             n_estimators=30, n_jobs=0, ...), 0.06969269470129122)
####  capacity_priority
Best model (XGBRegressor(alpha=0.012823829390192915, base_score=0.5, booster='gbtree',
             callbacks=None, colsample_bylevel=1, colsample_bynode=0.9,
             colsample_bytree=0.9, early_stopping_rounds=None,
             enable_categorical=False, eta=0.33318058063089306,
             eval_metric='mae', feature_types=None, gamma=0.06449766585100719,
             gpu_id=-1, grow_policy='depthwise', importance_type=None,
             interaction_constraints='', lambda=0.10166688193548182,
             learning_rate=0.333180577, max_bin=256, max_cat_threshold=64,
             max_cat_to_onehot=4, max_delta_step=0, max_depth=4, max_leaves=0,
             min_child_weight=3, missing=nan, monotone_constraints='()',
             n_estimators=30, n_jobs=0, ...), 61.265829688103516)
####  capacity_right_before_left
Best model (XGBRegressor(alpha=0.4820795585549199, base_score=0.5, booster='gbtree',
             callbacks=None, colsample_bylevel=1, colsample_bynode=0.9,
             colsample_bytree=0.9, early_stopping_rounds=None,
             enable_categorical=False, eta=0.34345978255494797,
             eval_metric='mae', feature_types=None, gamma=0.011470132097872277,
             gpu_id=-1, grow_policy='depthwise', importance_type=None,
             interaction_constraints='', lambda=0.07293005595696954,
             learning_rate=0.343459785, max_bin=256, max_cat_threshold=64,
             max_cat_to_onehot=4, max_delta_step=0, max_depth=4, max_leaves=0,
             min_child_weight=9, missing=nan, monotone_constraints='()',
             n_estimators=30, n_jobs=0, ...), 36.788928046413986)
####  capacity_traffic_light
Best model (XGBRegressor(alpha=0.023706692661408867, base_score=0.5, booster='gbtree',
             callbacks=None, colsample_bylevel=1, colsample_bynode=0.9,
             colsample_bytree=0.9, early_stopping_rounds=None,
             enable_categorical=False, eta=0.38082213314034685,
             eval_metric='mae', feature_types=None, gamma=0.14157989343524963,
             gpu_id=-1, grow_policy='depthwise', importance_type=None,
             interaction_constraints='', lambda=0.010045523253098031,
             learning_rate=0.380822122, max_bin=256, max_cat_threshold=64,
             max_cat_to_onehot=4, max_delta_step=0, max_depth=4, max_leaves=0,
             min_child_weight=6, missing=nan, monotone_constraints='()',
             n_estimators=30, n_jobs=0, ...), 133.86620550200644)

"""



