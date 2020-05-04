package org.carlspring.strongbox.gremlin.adapters;

import static org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality.single;
import static org.carlspring.strongbox.gremlin.adapters.EntityTraversalUtils.extractObject;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.carlspring.strongbox.artifact.ArtifactTag;
import org.carlspring.strongbox.db.schema.Edges;
import org.carlspring.strongbox.db.schema.Vertices;
import org.carlspring.strongbox.domain.Artifact;
import org.carlspring.strongbox.domain.ArtifactIdGroup;
import org.carlspring.strongbox.domain.ArtifactIdGroupEntity;
import org.carlspring.strongbox.gremlin.dsl.EntityTraversal;
import org.carlspring.strongbox.gremlin.dsl.__;
import org.springframework.stereotype.Component;

/**
 * @author sbespalov
 */
@Component
public class ArtifactIdGroupAdapter extends VertexEntityTraversalAdapter<ArtifactIdGroup>
{

    @Inject
    private ArtifactAdapter artifactAdapter;

    @Override
    public Set<String> labels()
    {
        return Collections.singleton(Vertices.ARTIFACT_ID_GROUP);
    }

    public EntityTraversal<Vertex, ArtifactIdGroup> fold(Optional<ArtifactTag> optionalTag)
    {
        EntityTraversal<Vertex, Vertex> artifactsTraversal = __.outE(Edges.ARTIFACT_GROUP_HAS_ARTIFACTS)
                                                               .inV();
        if (optionalTag.isPresent())
        {
            ArtifactTag tag = optionalTag.get();
            artifactsTraversal = artifactsTraversal.filter(__.outE(Edges.ARTIFACT_HAS_TAGS)
                                     .otherV()
                                     .has("uuid", tag.getName()));
        }
        else
        {
            artifactsTraversal = artifactsTraversal.optional(__.inE(Edges.REMOTE_ARTIFACT_INHERIT_ARTIFACT)
                                                               .otherV());
        }
        
        return __.<Vertex, Object>project("id", "uuid", "storageId", "repositoryId", "name", "artifacts")
                 .by(__.id())
                 .by(__.enrichPropertyValue("uuid"))
                 .by(__.enrichPropertyValue("storageId"))
                 .by(__.enrichPropertyValue("repositoryId"))
                 .by(__.enrichPropertyValue("name"))
                 .by(artifactsTraversal.map(artifactAdapter.fold())
                                       .map(EntityTraversalUtils::castToObject)
                                       .fold())
                 .map(this::map);
    }
    
    @Override
    public EntityTraversal<Vertex, ArtifactIdGroup> fold()
    {
        return fold(Optional.empty());
    }

    private ArtifactIdGroup map(Traverser<Map<String, Object>> t)
    {
        ArtifactIdGroupEntity result = new ArtifactIdGroupEntity(extractObject(String.class, t.get().get("storageId")),
                extractObject(String.class, t.get().get("repositoryId")), extractObject(String.class, t.get().get("name")));
        result.setNativeId(extractObject(Long.class, t.get().get("id")));
        result.setUuid(extractObject(String.class, t.get().get("uuid")));
        Collection<Artifact> artifacts = (Collection<Artifact>) t.get().get("artifacts");
        artifacts.stream().forEach(result::addArtifact);

        return result;
    }

    @Override
    public UnfoldEntityTraversal<Vertex, Vertex> unfold(ArtifactIdGroup entity)
    {
        EntityTraversal<Vertex, Vertex> saveArtifacstTraversal = __.<Vertex>identity();
        String storedArtifact = Vertices.ARTIFACT + ":" + UUID.randomUUID();
        for (Artifact artifact : entity.getArtifacts())
        {
            //cascading create Artifacts only
            if (artifact.getNativeId() != null)
            {
                continue;
            }
            UnfoldEntityTraversal<Vertex, Vertex> unfoldArtifactTraversal = artifactAdapter.unfold(artifact);
            saveArtifacstTraversal = saveArtifacstTraversal.V(artifact)
                                                           .saveV(artifact.getUuid(),
                                                                  unfoldArtifactTraversal)
                                                           .optional(__.outE(Edges.REMOTE_ARTIFACT_INHERIT_ARTIFACT).otherV())
                                                           .aggregate(storedArtifact);
        }

        String storedArtifactIdGroup = Vertices.ARTIFACT_ID_GROUP + ":" + UUID.randomUUID();
        EntityTraversal<Vertex, Vertex> unfoldTraversal = __.<Vertex, Vertex>map(unfoldArtifactGroup(entity))
                                                            .store(storedArtifactIdGroup)
                                                            .sideEffect(saveArtifacstTraversal.select(storedArtifact)
                                                                                              .unfold()
                                                                                              .addE(Edges.ARTIFACT_GROUP_HAS_ARTIFACTS)
                                                                                              .from(__.select(storedArtifactIdGroup)
                                                                                                      .unfold()));

        return new UnfoldEntityTraversal<>(Vertices.ARTIFACT_ID_GROUP, entity, unfoldTraversal);
    }

    private EntityTraversal<Vertex, Vertex> unfoldArtifactGroup(ArtifactIdGroup entity)
    {
        EntityTraversal<Vertex, Vertex> t = __.identity();
        //Skip update as ArtifactIdGroup assumed to be immutable 
        if (entity.getNativeId() != null) {
            return t;
        }

        if (entity.getStorageId() != null)
        {
            t = t.property(single, "storageId", entity.getStorageId());
        }
        if (entity.getRepositoryId() != null)
        {
            t = t.property(single, "repositoryId", entity.getRepositoryId());
        }
        if (entity.getName() != null)
        {
            t = t.property(single, "name", entity.getName());
        }

        return t;
    }

    @Override
    public EntityTraversal<Vertex, Element> cascade()
    {
        return __.<Vertex>aggregate("x")
                 .optional(__.outE(Edges.ARTIFACT_GROUP_HAS_ARTIFACTS)
                             .inV()
                             .optional(__.inE(Edges.REMOTE_ARTIFACT_INHERIT_ARTIFACT).otherV())
                             .flatMap(artifactAdapter.cascade()))
                 .select("x")
                 .unfold();
    }

}