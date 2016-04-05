package example;

import org.apache.commons.lang3.ArrayUtils;

import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.PerformsWrites;
import org.neo4j.procedure.Procedure;
import org.neo4j.tooling.GlobalGraphOperations;

import java.util.*;

/**
 * Created by lyonwj on 4/5/16.
 */
public class Louvain {
    private HashMap<Long, HashSet<Long>> nodeNeighbors = new HashMap<>(); // which nodes are key's neighbors
    private HashMap<Long, HashMap<Long, Double>> nodeNeighborsWeights = new HashMap<>(); // node neighbor weights
    private HashMap<Integer, HashSet<Long>> nodesInCommunity = new HashMap<>(); // which nodes are in key community
    private HashMap<Integer, HashSet<Long>> nodesInNodeCommunity = new HashMap<>(); // which nodes are in key node community
    private HashMap<Long, Integer> communityForNode = new HashMap<>(); // which community is a node in
    private HashMap<Long, Integer> nodeCommunityForNode = new HashMap<>(); // which node community is a node in
    private HashMap<Integer, Integer> nodeCommunitiesToCommunities = new HashMap<>(); // which communities map to node communities
    private ArrayList<Double> communityWeights;
    private long[] providers;
    private int N;
    private double resolution = 15.0;
    private Double graphWeightSum;
    private boolean communityUpdate = false;


    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;

    @Procedure("example.louvain")
    @PerformsWrites
    public void louvainMethod() {
        Map<String, String> results = new HashMap<String,String>(){{
            put("louvain method","calculated");
        }};

        // Get the node ids of the Providers in the graph
        providers = getProviders(db);
        // Get the count of Communities in the graph
        N = providers.length;
        System.out.println("After get Provider Count " + new java.util.Date());
        graphWeightSum = weightProviders(db);
        System.out.println("After gettting weight " + new java.util.Date());
        communityWeights = new ArrayList<>(N);
        for (int i = 0; i < N; i++)
        {
            communityWeights.add(0.0);
        }

        // Initialize
        for (int i = 0; i < providers.length; i++) {
            HashSet<Long> nodes = new HashSet<>();
            nodes.add(providers[i]);
            nodesInCommunity.put(i, nodes);
            nodesInNodeCommunity.put(i, (HashSet<Long>) nodes.clone());
            communityForNode.put(providers[i], i);
            nodeCommunityForNode.put(providers[i], i);
            nodeCommunitiesToCommunities.put(i, i);
        }
        System.out.println("After initialization " + new java.util.Date());

        Random rand = new Random();
        boolean someChange = true;
        while (someChange) {
            System.out.println("In computeModularity OUTER loop******************* " + new java.util.Date());
            someChange = false;
            boolean localChange = true;
            while (localChange) {
                localChange = false;
                int start = Math.abs(rand.nextInt()) % N;

                int step = 0;
                for (int i = start; step < N; i = (i + 1) % N) {
                    step++;

                    // Find the best community
                    // why is best community 0 when i is 0? It should be 1...
                    // I think the bug is in updateBestCommunity
                    int bestCommunity = updateBestCommunity(i);
                    if ((nodeCommunitiesToCommunities.get(i) != bestCommunity) && (this.communityUpdate)) {
                        moveNodeCommunity(i, bestCommunity);
                        double bestCommunityWeight = communityWeights.get(bestCommunity);

                        bestCommunityWeight += getNodeCommunityWeight(i);
                        communityWeights.set(bestCommunity, bestCommunityWeight);
                        localChange = true;
                    }

                    communityUpdate = false;
                }
                someChange = localChange || someChange;
            }
            if (someChange)
            {
                zoomOut();
            }
        }

        writeCommunities(db);
    }

    private void zoomOut() {
        N = reInitializeCommunities();
        communityWeights = new ArrayList<>(N);
        for (int i = 0; i < N; i++)
        {
            communityWeights.add(getCommunityWeight(i));
        }
    }

    public int reInitializeCommunities() {
        Map<Integer, Integer> initCommunities = new HashMap<>();
        int communityCounter = 0;
        nodesInCommunity.clear();
        nodesInNodeCommunity.clear();
        nodeCommunitiesToCommunities.clear();

        for (Long provider : providers) {
            Integer communityId = communityForNode.get(provider);
            if (!initCommunities.containsKey(communityId))
            {
                initCommunities.put(communityId, communityCounter);
                nodesInCommunity.put(communityCounter, new HashSet<>());
                nodesInNodeCommunity.put(communityCounter, new HashSet<>());
                communityCounter++;
            }
            int newCommunityId = initCommunities.get(communityId);
            communityForNode.put(provider, newCommunityId);
            nodeCommunityForNode.put(provider, newCommunityId);
            nodeCommunitiesToCommunities.put(newCommunityId, newCommunityId);
            nodesInCommunity.get(newCommunityId).add(provider);
            nodesInNodeCommunity.get(newCommunityId).add(provider);

        }

        return communityCounter;
    }

    private void writeCommunities(@Context GraphDatabaseService db) {
        int i = 0;
        Transaction tx = db.beginTx();
        try {

            for ( Map.Entry entry : nodesInCommunity.entrySet()) {
                Integer community = (Integer)entry.getKey();
                for (Long nodeId : (HashSet<Long>)entry.getValue()) {
                    Node node = db.getNodeById(nodeId);
                    node.setProperty("community", community);
                    node.setProperty("nodeCommunity", nodeCommunityForNode.get(nodeId));
                    i++;

                    // Commit every x updates
                    if (i % 10000 == 0) {
                        tx.success();
                        tx.close();
                        tx = db.beginTx();
                    }
                }
            }

            tx.success();
        } finally {
            tx.close();
        }
    }

    private void moveNodeCommunity(int nodeCommunity, int toCommunity) {
        int fromCommunity = nodeCommunitiesToCommunities.get(nodeCommunity);
        nodeCommunitiesToCommunities.put(nodeCommunity, toCommunity);
        Set<Long> nodesFromCommunity = nodesInCommunity.get(fromCommunity);

        nodesInCommunity.remove(fromCommunity);
        nodesInCommunity.get(toCommunity).addAll(nodesFromCommunity);

        for (Long nodeId : nodesFromCommunity) {
            nodeCommunitiesToCommunities.put(nodeCommunityForNode.get(nodeId), toCommunity); // I hope?
            communityForNode.put(nodeId, toCommunity);
        }
    }

    private int updateBestCommunity(Integer nodeCommunity ) {
        int bestCommunity = 0;
        double best = 0;
        // Get Communities Connected To Node Communities
        Set<Integer> communities = new HashSet<>();
        for (long nodeId : nodesInNodeCommunity.get(nodeCommunity)) {
            for (long neighborId : nodeNeighbors.get(nodeId)) {
                communities.add(communityForNode.get(neighborId));
            }
        }
        for (Integer community : communities) {
            double qValue = q(nodeCommunity, community);
            if (qValue > best)
            {
                best = qValue;
                bestCommunity = community;
                communityUpdate = true;
            }
        }
        return bestCommunity;
    }

    private double q(Integer nodeCommunity, Integer community) {
        double edgesInCommunity = getEdgesInsideCommunity(nodeCommunity, community);
        double communityWeight = communityWeights.get(community);
        double nodeWeight = getNodeCommunityWeight(nodeCommunity);
        double qValue = resolution * edgesInCommunity - (nodeWeight * communityWeight)
                / (2.0 * graphWeightSum);
        int actualNodeCom = nodeCommunitiesToCommunities.get(nodeCommunity);
        int communitySize = nodesInCommunity.get(community).size();

        if ((actualNodeCom == community) && (communitySize > 1))
        {
            qValue = resolution * edgesInCommunity - (nodeWeight * (communityWeight - nodeWeight))
                    / (2.0 * graphWeightSum);
        }
        if ((actualNodeCom == community) && (communitySize == 1))
        {
            qValue = 0.0;
        }
        return qValue;
    }

    private double getCommunityWeight(Integer community)
    {
        double communityWeight = 0.0;
        for (Long communityNode : nodesInCommunity.get(community)) {
            for (Double weight : nodeNeighborsWeights.get(communityNode).values()) {
                communityWeight += weight;
            }
        }

        return communityWeight;
    }

    private double getNodeCommunityWeight(Integer nodeCommunity)
    {
        double communityWeight = 0.0;
        for (Long communityNode : nodesInNodeCommunity.get(nodeCommunity)) {
            for (Double weight : nodeNeighborsWeights.get(communityNode).values()) {
                communityWeight += weight;
            }
        }

        return communityWeight;
    }

    private double getEdgesInsideCommunity(Integer nodeCommunity, Integer community) {
        Set<Long> nodeCommunityNodes = nodesInNodeCommunity.get(nodeCommunity);
        Set<Long> communityNodes = nodesInCommunity.get(community);
        double edges = 0;
        for (Long nodeCommunityNode : nodeCommunityNodes)
        {
            for (Long communityNode : communityNodes)
            {
                if (nodeNeighbors.get(nodeCommunityNode).contains(communityNode))
                {
                    edges += nodeNeighborsWeights.get(nodeCommunityNode).get(communityNode);
                }
            }
        }
        return edges;
    }

    private long[] getProviders(GraphDatabaseService db) {
        ArrayList<Long> providerList = new ArrayList<>();
        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> providers = db.findNodes(Labels.Character);
            while (providers.hasNext()) {
                HashSet<Long> neighbors = new HashSet<>();
                HashMap<Long, Double> neighborWeights = new HashMap<>();
                Node provider = providers.next();
                providerList.add(provider.getId());
                for (Relationship rel : provider.getRelationships(RelationshipTypes.INTERACTS, Direction.OUTGOING)) {
                    neighbors.add(rel.getEndNode().getId());
                    neighborWeights.put(rel.getEndNode().getId(), (double) rel.getProperty("weight", 0.0D));
                }
                nodeNeighbors.put(provider.getId(), neighbors);
                nodeNeighborsWeights.put(provider.getId(), neighborWeights);
            }
            tx.success();
        }

        return ArrayUtils.toPrimitive(providerList.toArray(new Long[providerList.size()]));
    }

    private double weightProviders(GraphDatabaseService db) {
        double sum = 0.0;
        try (Transaction tx = db.beginTx()) {
            for (Relationship relationship : GlobalGraphOperations.at(db).getAllRelationships()) {
                if (relationship.isType(RelationshipTypes.INTERACTS)) {
                    sum += (double)relationship.getProperty("weight", 0.0);
                }
            }
            tx.success();
        }

        return sum;
    }

}
