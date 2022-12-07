#!/usr/bin/env python3.7
import itertools
import os
import subprocess
import shutil

job_directory = "jobscripts"
os.makedirs(job_directory, exist_ok=True)

trace_dir = "/home/radu/Thesis/wta-sim/input_traces/"
output_location = "/home/radu/Thesis/wta-sim/results/sim_output/"
slack_location = "/home/radu/Thesis/wta-sim/results/look_ahead/"
test_input = "pegasus_p7_parquet"

for filename in os.listdir(output_location):
    file_path = os.path.join(output_location, filename)
    try:
        if os.path.isfile(file_path) or os.path.islink(file_path):
            os.unlink(file_path)
        elif os.path.isdir(file_path):
            shutil.rmtree(file_path)
    except Exception as e:
        print('Failed to delete %s.\nReason: %s' % (file_path, e))
            

# Laurens machines
# machine_resources = [128, 12]
# machine_tdps = [280, 95]
# machine_base_clocks = [2.9, 4.1]
# machine_fractions = [0.5, 0.5]

# My machines
# AMD Epyc 7763 with cTDP set to 225 (normal TDP = 280W)
machine_resources = [64]
machine_tdps = [225]
machine_base_clocks = [2.45]
machine_fractions = [1]
    

# Variations to try:
## Laurens variations:
# target_utilizations = [0.4]  # 40% is reasonable for hyper scale datacenters - https://www.nrdc.org/sites/default/files/data-center-efficiency-assessment-IP.pdf
# task_selection_policies = ["fcfs"]
# task_placement_policies = ["look_ahead", "fastest_machine"]
# dvfs_enabled = [True, False]

## -------- Finalized experiments -------
number_of_machines_per_DC = ["9"]
task_selection_policies = ["fcfs"]
task_placement_policies = ["fastest_machine"]#, "look_ahead"]
dvfs = True
datacentres = ["2"]#, "2"]


## -------- Test for env_stats ---------
# number_of_machines_per_DC = ["2"]
# tsp = "fcfs"
# tpp = "look_ahead"
# dvfs = True
# datacentres = ["2"]


subprocess.run("mvn package", shell=True)

for folder in next(os.walk(trace_dir))[1]:
    # if folder == "alibaba_from_flat" or folder == "alibaba_first_10k_wfids_parquet":
    #     continue  # Do not load the entire (too big) or the smaller 10k alibaba trace (for testing)

    # if "google" in str(folder).lower(): continue
    # if "lanl" in str(folder).lower(): 
    #     # print("isLANL")
    #     continue
    # if "two_sigma" in str(folder).lower(): continue
    if test_input not in str(folder).lower(): 
        continue
    print("***************found it*****************")
    for tpp, dcs in itertools.product(task_placement_policies, datacentres):

        experiment_name = f"{folder}_tpp_{tpp}_dcs_{dcs}_roundRobin"
        experiment_name = f"{folder}_tpp_{tpp}_dcs_{dcs}_roundRobin"
        # experiment_name = f"{folder}_ENV_STATS_TEST"
        output_dir = os.path.join(output_location, experiment_name)
        if os.path.exists(output_dir):
            continue
        job_file = os.path.join(job_directory, f"{experiment_name}.job")

        with open(job_file, "w") as fh:
            command = "java -Xmx60g -cp /home/radu/Thesis/wta-sim/target/wta-sim-0.1.jar science.atlarge.wta.simulator.WTASim -f wta"
            command += " -c " + " ".join([str(x) for x in machine_resources])
            command += " -t " + " ".join([str(x) for x in machine_tdps])
            command += " -bc " + " ".join([str(x) for x in machine_base_clocks])
            command += " -mf " + " ".join([str(x) for x in machine_fractions])
            command += " -sd " + slack_location
            command += " -e " + " ".join([str(x) for x in [dvfs] * len(machine_resources)])
            command += " -i " + os.path.join(trace_dir, folder)
            command += " -o " + output_dir
            # command += " --target-utilization " + str(tu)
            command += " -m " + " ".join([str(x) for x in number_of_machines_per_DC])
            command += " -dcs " + dcs
            command += " --task-order-policy " + " ".join([str(x) for x in task_selection_policies])
            command += " --task-placement-policy " + tpp
            # command += " --output " + experiment_name + ".out"

            # fh.writelines("#!/bin/bash\n")
            # fh.writelines(f"#SBATCH --job-name={experiment_name}.job\n")
            # fh.writelines(f"#SBATCH --output={experiment_name}.out\n")
            # fh.writelines(f"#SBATCH --error={experiment_name}.err\n")
            # fh.writelines("#SBATCH --time=48-00:00\n")
            # fh.writelines(f"{command}\n")

            os.system(command)
        # os.system(f"sbatch {job_file}")
